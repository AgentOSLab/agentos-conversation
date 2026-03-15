package com.agentos.conversation.orchestration;

import com.agentos.conversation.model.dto.RunEvent;
import com.agentos.conversation.model.dto.RunEvent.DisplayMetadata;
import com.agentos.conversation.service.RunService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Multi-instance-safe event aggregator. All state lives in Redis:
 *
 *   run_events:{runId}    — Sorted Set: event buffer for replay (score = seq)
 *   run_seq:{runId}       — Atomic counter: monotonic eventId via INCR
 *   run_channel:{runId}   — Pub/Sub: real-time event distribution
 *   run_meta:{runId}      — Hash: runId→sessionId mapping for cross-instance lookup
 *
 * Any instance can:
 *   - Publish events (ZADD + PUBLISH)
 *   - Serve SSE (SUBSCRIBE + ZRANGEBYSCORE for replay)
 *   - Handle cancel/input (stateless via DB + Redis publish)
 *
 * Agent Runtime events are consumed via Redis Pub/Sub (events:{tenantId}:{taskId})
 * instead of HTTP SSE, eliminating the cross-instance HTTP connection problem.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseAggregator {

    private static final String EVENT_BUFFER_PREFIX = "run_events:";
    private static final String EVENT_SEQ_PREFIX = "run_seq:";
    private static final String EVENT_CHANNEL_PREFIX = "run_channel:";
    private static final String RUN_META_PREFIX = "run_meta:";
    private static final Duration EVENT_BUFFER_TTL = Duration.ofMinutes(15);

    private static final Set<String> TERMINAL_TASK_EVENTS = Set.of(
            "task.completed", "task.failed", "task.cancelled", "task.timeout");

    private final RunService runService;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // ─────────────── Public API: Event Publishing ───────────────

    /**
     * Register an agent task run. Subscribes to Agent Runtime's Redis Pub/Sub
     * channel for the task and bridges events to the run's event channel.
     * This runs on whichever instance creates the run — but since events flow
     * through Redis, any instance can serve the SSE stream.
     */
    public Mono<Void> registerAgentTaskRun(UUID runId, UUID taskId, UUID sessionId,
                                            UUID tenantId) {
        return storeRunMeta(runId, sessionId)
                .then(publishEvent(runId, sessionId, "run_started",
                        DisplayMetadata.builder().category("execution").priority("important")
                                .summary("Run started").build(), null))
                .then(Mono.defer(() -> {
                    String taskChannel = "events:" + tenantId + ":" + taskId;
                    log.info("Run {} subscribing to Agent Runtime channel: {}", runId, taskChannel);

                    redisTemplate.listenTo(ChannelTopic.of(taskChannel))
                            .map(ReactiveSubscription.Message::getMessage)
                            .takeUntil(msg -> isTerminalTaskEvent(msg))
                            .concatMap(msg -> normalizeAndPublish(msg, runId, sessionId))
                            .doOnComplete(() -> {
                                log.info("Agent Runtime event stream completed for run {}", runId);
                                publishTerminalEvents(runId, sessionId, "completed").subscribe();
                            })
                            .doOnError(e -> {
                                log.error("Agent Runtime event stream error for run {}: {}", runId, e.getMessage());
                                publishErrorAndFail(runId, sessionId, "agent_runtime_error", e.getMessage()).subscribe();
                            })
                            .subscribe();

                    return Mono.empty();
                }));
    }

    /**
     * Publish a single event for a run. Used by the orchestrator for simple
     * chat token streaming. Fully Redis-backed — works across instances.
     */
    public Mono<Void> publishEvent(UUID runId, UUID sessionId, String type,
                                    DisplayMetadata display, Map<String, Object> extra) {
        return nextSequence(runId)
                .flatMap(seq -> {
                    String eventId = formatEventId(runId, seq);
                    RunEvent event = RunEvent.builder()
                            .eventId(eventId)
                            .runId(runId)
                            .sessionId(sessionId)
                            .type(type)
                            .timestamp(OffsetDateTime.now())
                            .display(display)
                            .build();

                    if (extra != null) applyExtra(event, extra);

                    return bufferAndPublish(runId, event, seq);
                });
    }

    /**
     * Publish a token event. Optimized path for high-frequency token streaming.
     */
    public Mono<Void> publishToken(UUID runId, UUID sessionId, String content, int index) {
        return nextSequence(runId)
                .flatMap(seq -> {
                    String eventId = formatEventId(runId, seq);
                    RunEvent event = RunEvent.builder()
                            .eventId(eventId)
                            .runId(runId)
                            .sessionId(sessionId)
                            .type("token")
                            .timestamp(OffsetDateTime.now())
                            .display(DisplayMetadata.builder()
                                    .category("output").priority("detail").build())
                            .content(content)
                            .index(index)
                            .build();

                    return bufferAndPublish(runId, event, seq);
                });
    }

    /**
     * Publish a thinking event for LLM reasoning output.
     */
    public Mono<Void> publishThinking(UUID runId, UUID sessionId, String content) {
        return nextSequence(runId)
                .flatMap(seq -> {
                    String eventId = formatEventId(runId, seq);
                    RunEvent event = RunEvent.builder()
                            .eventId(eventId)
                            .runId(runId)
                            .sessionId(sessionId)
                            .type("thinking")
                            .timestamp(OffsetDateTime.now())
                            .display(DisplayMetadata.builder()
                                    .category("thinking").priority("important")
                                    .summary("Thinking...").build())
                            .content(content)
                            .build();

                    return bufferAndPublish(runId, event, seq);
                });
    }

    /**
     * Publish a context_usage event so the frontend can display context utilization.
     */
    public Mono<Void> publishContextUsage(UUID runId, UUID sessionId,
                                           Map<String, Object> contextUsage) {
        return nextSequence(runId)
                .flatMap(seq -> {
                    String eventId = formatEventId(runId, seq);
                    RunEvent event = RunEvent.builder()
                            .eventId(eventId)
                            .runId(runId)
                            .sessionId(sessionId)
                            .type("context_usage")
                            .timestamp(OffsetDateTime.now())
                            .display(DisplayMetadata.builder()
                                    .category("detail").priority("important")
                                    .summary("Context usage").build())
                            .contextUsage(contextUsage)
                            .build();

                    return bufferAndPublish(runId, event, seq);
                });
    }

    /**
     * Publish message_started event.
     */
    public Mono<Void> publishMessageStarted(UUID runId, UUID sessionId) {
        return publishEvent(runId, sessionId, "message_started",
                DisplayMetadata.builder().category("output").priority("important")
                        .summary("Assistant response").build(), null);
    }

    /**
     * Finalize a run: message_completed → run_completed → done.
     */
    public Mono<Void> publishRunFinalized(UUID runId, UUID sessionId, UUID messageId,
                                           String content, Map<String, Object> usage) {
        return publishEvent(runId, sessionId, "message_completed",
                        DisplayMetadata.builder().category("output").priority("important")
                                .summary("Message complete").build(),
                        Map.of("messageId", messageId.toString(), "content", content))
                .then(publishEvent(runId, sessionId, "run_completed",
                        DisplayMetadata.builder().category("output").priority("important")
                                .summary("Run completed").build(),
                        usage != null ? Map.of("usage", usage) : null))
                .then(publishDone(runId, sessionId, "completed"));
    }

    /**
     * Publish error + run_failed + done sequence.
     */
    public Mono<Void> publishErrorAndFail(UUID runId, UUID sessionId,
                                           String errorCode, String errorMessage) {
        return publishEvent(runId, sessionId, "error",
                        DisplayMetadata.builder().category("output").priority("important")
                                .summary("Error").detail(errorMessage).build(),
                        Map.of("error", Map.of("code", errorCode,
                                "message", errorMessage, "retryable", true)))
                .then(publishEvent(runId, sessionId, "run_failed",
                        DisplayMetadata.builder().category("output").priority("important")
                                .summary("Run failed").build(), null))
                .then(publishDone(runId, sessionId, "failed"))
                .then(runService.failRun(runId, errorCode, errorMessage, true));
    }

    /**
     * Publish run_cancelled + done. Works from any instance.
     */
    public Mono<Void> publishRunCancelled(UUID runId) {
        return getSessionIdForRun(runId)
                .flatMap(sessionId ->
                        publishEvent(runId, sessionId, "run_cancelled",
                                DisplayMetadata.builder().category("output").priority("important")
                                        .summary("Run cancelled by user").build(), null)
                        .then(publishDone(runId, sessionId, "cancelled")));
    }

    // ─────────────── Public API: SSE Streaming ───────────────

    /**
     * Stream SSE events for a run. Any instance can serve this — all data
     * comes from Redis (replay from Sorted Set, live from Pub/Sub).
     */
    public Flux<ServerSentEvent<String>> streamRunEvents(UUID runId, UUID sessionId,
                                                          UUID tenantId, UUID userId,
                                                          String lastEventId) {
        long startSeq = lastEventId != null ? extractSequence(lastEventId) : 0;

        Flux<RunEvent> replayEvents = replayFromRedis(runId, startSeq);

        String channel = EVENT_CHANNEL_PREFIX + runId;
        Flux<RunEvent> liveEvents = redisTemplate.listenTo(ChannelTopic.of(channel))
                .map(ReactiveSubscription.Message::getMessage)
                .mapNotNull(this::deserializeEvent)
                .filter(event -> extractSequence(event.getEventId()) > startSeq);

        return Flux.concat(replayEvents, liveEvents)
                .takeUntil(event -> "done".equals(event.getType()))
                .map(event -> ServerSentEvent.<String>builder()
                        .id(event.getEventId())
                        .event(event.getType())
                        .data(toJson(event))
                        .build())
                .doOnSubscribe(s -> log.debug("SSE stream opened for run {} (lastEventId={})", runId, lastEventId))
                .doOnCancel(() -> log.debug("SSE client disconnected for run {}", runId));
    }

    // ─────────────── Agent Runtime Event Normalization ───────────────

    @SuppressWarnings("unchecked")
    private Mono<Void> normalizeAndPublish(String rawEvent, UUID runId, UUID sessionId) {
        try {
            Map<String, Object> event = objectMapper.readValue(rawEvent, Map.class);
            String eventType = String.valueOf(event.getOrDefault("eventType",
                    event.getOrDefault("type", "progress")));

            Map<String, Object> payload = event.containsKey("payload")
                    ? (Map<String, Object>) event.get("payload")
                    : event;

            String mappedType = mapEventType(eventType);
            DisplayMetadata display = buildDisplayFromAgentEvent(eventType, payload, event);

            return nextSequence(runId)
                    .flatMap(seq -> {
                        String eventId = formatEventId(runId, seq);
                        RunEvent runEvent = RunEvent.builder()
                                .eventId(eventId)
                                .runId(runId)
                                .sessionId(sessionId)
                                .type(mappedType)
                                .timestamp(OffsetDateTime.now())
                                .display(display)
                                .build();

                        applyAgentRuntimePayload(runEvent, eventType, payload);

                        if ("waiting_for_input".equals(mappedType)) {
                            return bufferAndPublish(runId, runEvent, seq)
                                    .then(runService.updateStatus(runId, "waiting_for_input"));
                        }

                        return bufferAndPublish(runId, runEvent, seq);
                    });
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse Agent Runtime event: {}", e.getMessage());
            return publishEvent(runId, sessionId, "progress",
                    DisplayMetadata.builder().category("execution").priority("detail")
                            .summary("Raw event").build(), null);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyAgentRuntimePayload(RunEvent runEvent, String type,
                                           Map<String, Object> payload) {
        switch (type) {
            case "thinking", "reasoning" -> runEvent.setContent(strVal(payload, "content"));
            case "token" -> {
                runEvent.setContent(strVal(payload, "content"));
                runEvent.setIndex(intVal(payload, "index"));
            }
            case "tool_call" -> {
                runEvent.setToolCallId(strVal(payload, "toolCallId"));
                runEvent.setToolName(strVal(payload, "toolName"));
                runEvent.setArguments((Map<String, Object>) payload.get("arguments"));
            }
            case "tool_result" -> {
                runEvent.setToolCallId(strVal(payload, "toolCallId"));
                runEvent.setToolName(strVal(payload, "toolName"));
                runEvent.setResult((Map<String, Object>) payload.get("result"));
            }
            case "tool_error" -> {
                runEvent.setToolCallId(strVal(payload, "toolCallId"));
                runEvent.setToolName(strVal(payload, "toolName"));
                runEvent.setError((Map<String, Object>) payload.get("error"));
            }
            case "human_input_required", "waiting_for_input" -> {
                runEvent.setInteractionId(strVal(payload, "interactionId"));
                runEvent.setInteractionType(strVal(payload, "interactionType"));
                runEvent.setPrompt(strVal(payload, "prompt"));
            }
            case "mode_selected" -> runEvent.setContent(strVal(payload, "mode"));
            case "mode_escalated" -> runEvent.setContent(strVal(payload, "newMode"));
            case "task_completed", "task.completed", "completed" -> {
                runEvent.setType("run_completed");
                Map<String, Object> usage = (Map<String, Object>) payload.get("usage");
                if (usage != null) runEvent.setUsage(usage);
            }
            case "task_failed", "task.failed", "failed" -> {
                runEvent.setType("run_failed");
                runEvent.setError((Map<String, Object>) payload.get("error"));
            }
            case "error" -> runEvent.setError((Map<String, Object>) payload.get("error"));
            default -> runEvent.setContent(strVal(payload, "message"));
        }
    }

    private String mapEventType(String agentRuntimeType) {
        return switch (agentRuntimeType) {
            case "reasoning" -> "thinking";
            case "human_input_required" -> "waiting_for_input";
            case "task_completed", "task.completed", "completed" -> "run_completed";
            case "task_failed", "task.failed", "failed" -> "run_failed";
            case "task.cancelled" -> "run_cancelled";
            default -> agentRuntimeType;
        };
    }

    private boolean isTerminalTaskEvent(String rawMessage) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = objectMapper.readValue(rawMessage, Map.class);
            Object eventType = event.get("eventType");
            return eventType != null && TERMINAL_TASK_EVENTS.contains(eventType.toString());
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private DisplayMetadata buildDisplayFromAgentEvent(String type,
                                                       Map<String, Object> payload,
                                                       Map<String, Object> fullEvent) {
        Map<String, Object> display = (Map<String, Object>) fullEvent.get("display");
        if (display == null) display = (Map<String, Object>) fullEvent.get("displayMetadata");
        if (display != null) {
            return DisplayMetadata.builder()
                    .category(strVal(display, "displayCategory"))
                    .priority(strVal(display, "displayPriority"))
                    .summary(strVal(display, "summary"))
                    .detail(strVal(display, "detail"))
                    .build();
        }

        return switch (type) {
            case "thinking", "reasoning" -> DisplayMetadata.builder()
                    .category("thinking").priority("important").summary("Thinking...").build();
            case "tool_call" -> DisplayMetadata.builder()
                    .category("execution").priority("important")
                    .summary("Calling " + strVal(payload, "toolName")).build();
            case "tool_result" -> DisplayMetadata.builder()
                    .category("execution").priority("important")
                    .summary("Result from " + strVal(payload, "toolName")).build();
            case "tool_error" -> DisplayMetadata.builder()
                    .category("execution").priority("important")
                    .summary("Error in " + strVal(payload, "toolName")).build();
            case "human_input_required", "waiting_for_input" -> DisplayMetadata.builder()
                    .category("interaction").priority("important")
                    .summary("Waiting for your input").build();
            case "mode_selected" -> DisplayMetadata.builder()
                    .category("execution").priority("important")
                    .summary("Execution mode: " + strVal(payload, "mode")).build();
            default -> DisplayMetadata.builder()
                    .category("execution").priority("detail")
                    .summary(strVal(payload, "message")).build();
        };
    }

    // ─────────────── Redis Operations ───────────────

    private Mono<Long> nextSequence(UUID runId) {
        return redisTemplate.opsForValue()
                .increment(EVENT_SEQ_PREFIX + runId)
                .doOnNext(seq -> {
                    if (seq == 1L) {
                        redisTemplate.expire(EVENT_SEQ_PREFIX + runId, EVENT_BUFFER_TTL).subscribe();
                    }
                });
    }

    private Mono<Void> bufferAndPublish(UUID runId, RunEvent event, long seq) {
        String json = toJson(event);
        String bufferKey = EVENT_BUFFER_PREFIX + runId;
        String channelKey = EVENT_CHANNEL_PREFIX + runId;

        return redisTemplate.opsForZSet().add(bufferKey, json, seq)
                .then(redisTemplate.expire(bufferKey, EVENT_BUFFER_TTL))
                .then(redisTemplate.convertAndSend(channelKey, json))
                .then(runService.updateLastEventId(runId, event.getEventId()))
                .then();
    }

    private Flux<RunEvent> replayFromRedis(UUID runId, long afterSeq) {
        String bufferKey = EVENT_BUFFER_PREFIX + runId;

        return redisTemplate.opsForZSet()
                .rangeByScore(bufferKey,
                        org.springframework.data.domain.Range.open(
                                (double) afterSeq, Double.MAX_VALUE))
                .mapNotNull(this::deserializeEvent);
    }

    private Mono<Void> storeRunMeta(UUID runId, UUID sessionId) {
        String metaKey = RUN_META_PREFIX + runId;
        return redisTemplate.opsForValue()
                .set(metaKey, sessionId.toString(), EVENT_BUFFER_TTL)
                .then();
    }

    private Mono<UUID> getSessionIdForRun(UUID runId) {
        return redisTemplate.opsForValue()
                .get(RUN_META_PREFIX + runId)
                .map(UUID::fromString)
                .switchIfEmpty(runService.getRun(runId, null)
                        .map(run -> run.getSessionId()));
    }

    private Mono<Void> publishDone(UUID runId, UUID sessionId, String finalStatus) {
        return nextSequence(runId)
                .flatMap(seq -> {
                    String eventId = formatEventId(runId, seq);
                    RunEvent event = RunEvent.builder()
                            .eventId(eventId)
                            .runId(runId)
                            .sessionId(sessionId)
                            .type("done")
                            .timestamp(OffsetDateTime.now())
                            .finalStatus(finalStatus)
                            .build();

                    return bufferAndPublish(runId, event, seq);
                });
    }

    private Mono<Void> publishTerminalEvents(UUID runId, UUID sessionId, String status) {
        return publishEvent(runId, sessionId, "run_completed",
                        DisplayMetadata.builder().category("output").priority("important")
                                .summary("Run completed").build(), null)
                .then(publishDone(runId, sessionId, status))
                .then(runService.completeRun(runId, null, null, null));
    }

    // ─────────────── Helpers ───────────────

    @SuppressWarnings("unchecked")
    private void applyExtra(RunEvent event, Map<String, Object> extra) {
        if (extra == null) return;
        if (extra.containsKey("content")) event.setContent((String) extra.get("content"));
        if (extra.containsKey("messageId")) {
            event.setMessageId(UUID.fromString((String) extra.get("messageId")));
        }
        if (extra.containsKey("usage")) {
            event.setUsage((Map<String, Object>) extra.get("usage"));
        }
        if (extra.containsKey("contextUsage")) {
            event.setContextUsage((Map<String, Object>) extra.get("contextUsage"));
        }
        if (extra.containsKey("error")) {
            event.setError((Map<String, Object>) extra.get("error"));
        }
        if (extra.containsKey("finalStatus")) {
            event.setFinalStatus((String) extra.get("finalStatus"));
        }
    }

    private String formatEventId(UUID runId, long seq) {
        return "run:" + runId + ":seq:" + String.format("%06d", seq);
    }

    private long extractSequence(String eventId) {
        if (eventId == null) return 0;
        try {
            String[] parts = eventId.split(":seq:");
            return parts.length > 1 ? Long.parseLong(parts[1]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private RunEvent deserializeEvent(String json) {
        try {
            return objectMapper.readValue(json, RunEvent.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize event: {}", e.getMessage());
            return null;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("JSON serialization failed: {}", e.getMessage());
            return "{}";
        }
    }

    private String strVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    private Integer intVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return null;
    }
}
