package com.agentos.conversation.orchestration;

import com.agentos.conversation.model.dto.RunEvent;
import com.agentos.conversation.model.dto.RunEvent.DisplayMetadata;
import com.agentos.conversation.service.RunService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    /** Session-level Pub/Sub channel — mirrors all run events for a session. */
    private static final String SESSION_CHANNEL_PREFIX = "session_channel:";

    /**
     * Lua script that atomically writes an event to the sorted-set buffer AND
     * publishes it to the Pub/Sub channel in a single server-side operation.
     *
     * Atomicity guarantee (P3 fix): Because ZADD + EXPIRE + PUBLISH execute in a
     * single script, a Pub/Sub subscriber that receives an event is guaranteed to
     * find that event in the sorted-set buffer on reconnect.  Without this script,
     * a client could receive an event via PUBLISH before ZADD completes (due to
     * concurrent thread interleaving), and then miss it on replay.
     *
     * Note: INCR (sequence assignment) still runs separately before this script.
     * This is intentional: we cannot embed the seq in the event JSON and ZADD it
     * inside the same atomic INCR call.  The remaining (benign) race is that two
     * concurrent publishers may deliver events via PUBLISH in a different order than
     * their sequence numbers; clients sort by the embedded sequence number, so
     * display ordering is always correct.
     *
     * KEYS: [1] bufferKey  (run_events:{runId})
     *       [2] channelKey (run_channel:{runId})
     * ARGV: [1] seq (double/long — used as ZADD score)
     *       [2] event JSON (stored in sorted set and published)
     *       [3] TTL seconds (integer)
     * Returns: 1 (success).
     */
    private static final RedisScript<Long> ZADD_AND_PUBLISH_SCRIPT = RedisScript.of(
            "redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])\n" +
            "redis.call('EXPIRE', KEYS[1], ARGV[3])\n" +
            "redis.call('PUBLISH', KEYS[2], ARGV[2])\n" +
            "return 1",
            Long.class);

    /**
     * TTL for the Redis event buffer Sorted Set (and associated metadata keys).
     * Must be longer than the longest expected agent task execution time so that
     * clients reconnecting mid-task can still replay missed events.
     * Default 60 minutes; override with {@code agentos.sse.event-buffer-ttl-minutes}.
     */
    @Value("${agentos.sse.event-buffer-ttl-minutes:60}")
    private int eventBufferTtlMinutes;

    /**
     * Maximum wall-clock time a client may hold a single Run SSE connection. When elapsed without
     * a natural {@code done} event, the run is failed with {@code sse_stream_timeout} and terminal
     * events are replayed from Redis so the client receives {@code error}/{@code run_failed}/{@code done}.
     */
    @Value("${agentos.sse.stream-max-duration-minutes:120}")
    private int streamMaxDurationMinutes;

    /**
     * Interval for SSE comment keep-alives on Run streams ({@code :keepalive}). Zero disables.
     * Helps proxies and CDNs detect liveness during long agent runs with sparse events.
     */
    @Value("${agentos.sse.heartbeat-interval-seconds:30}")
    private int heartbeatIntervalSeconds;

    private static final Set<String> TERMINAL_TASK_EVENTS = Set.of(
            "task.completed", "task.failed", "task.cancelled", "task.timeout");

    private final RunService runService;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * P4-HITL fix: configurable event type mapping registry.
     *
     * Default mappings normalize Agent Runtime event types to Run-level event types.
     * Additional mappings can be injected via Spring config property
     * {@code agentos.sse.event-type-mappings} (comma-separated key=value pairs,
     * e.g. {@code "custom.event=custom_mapped,another.event=mapped_type"}).
     */
    private static final Map<String, String> DEFAULT_EVENT_TYPE_MAP = Map.ofEntries(
            Map.entry("step.llm.thinking", "thinking"),
            Map.entry("reasoning", "thinking"),
            Map.entry("human_input_required", "waiting_for_input"),
            Map.entry("task_completed", "run_completed"),
            Map.entry("task.completed", "run_completed"),
            Map.entry("completed", "run_completed"),
            Map.entry("task_failed", "run_failed"),
            Map.entry("task.failed", "run_failed"),
            Map.entry("failed", "run_failed"),
            Map.entry("task.cancelled", "run_cancelled"),
            Map.entry("task.mode_escalation_requested", "mode_escalation_requested"),
            Map.entry("task.mode_escalated", "mode_escalated"),
            Map.entry("skill.hitl.required", "waiting_for_input"),
            Map.entry("subagent.hitl.required", "waiting_for_input"),
            // GAP-HITL-001 fix: MCP tool authorization gets a distinct SSE type so frontends
            // can render an "Approve tool operation?" UI (not the generic waiting_for_input).
            Map.entry("mcp.hitl.required", "operation_authorization"),
            Map.entry("hitl.required", "waiting_for_input"),
            // Normalized aliases for frontends expecting explicit HITL event names
            Map.entry("task.hitl.required", "hitl_request"),
            Map.entry("task.hitl.resolved", "hitl_resolved"),
            Map.entry("step.completed", "step_completed"),
            Map.entry("step.failed", "step_failed")
    );

    /**
     * P4-HITL: custom event type mappings from config, merged with defaults at startup.
     * Format: comma-separated {@code source=target} pairs.
     * Example: {@code agentos.sse.event-type-mappings=custom.event=custom_mapped}
     */
    @Value("${agentos.sse.event-type-mappings:}")
    private String customEventTypeMappings;

    private volatile Map<String, String> mergedEventTypeMap;

    private Map<String, String> eventTypeMap() {
        if (mergedEventTypeMap == null) {
            synchronized (this) {
                if (mergedEventTypeMap == null) {
                    Map<String, String> merged = new java.util.HashMap<>(DEFAULT_EVENT_TYPE_MAP);
                    if (customEventTypeMappings != null && !customEventTypeMappings.isBlank()) {
                        for (String pair : customEventTypeMappings.split(",")) {
                            String[] kv = pair.trim().split("=", 2);
                            if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) {
                                merged.put(kv[0].trim(), kv[1].trim());
                                log.info("P4-HITL: custom event type mapping registered: {} → {}",
                                        kv[0].trim(), kv[1].trim());
                            }
                        }
                    }
                    mergedEventTypeMap = Map.copyOf(merged);
                }
            }
        }
        return mergedEventTypeMap;
    }

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
     *
     * <p>Bounded by {@link #streamMaxDurationMinutes}: if no {@code done} within that window,
     * {@link #publishErrorAndFail} is invoked and new terminal events are replayed for this client.
     * Optional comment heartbeats are controlled by {@link #heartbeatIntervalSeconds}.
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

        AtomicBoolean sawDone = new AtomicBoolean(false);
        AtomicLong lastSeq = new AtomicLong(startSeq);

        Flux<RunEvent> source = Flux.concat(replayEvents, liveEvents)
                .doOnNext(event -> {
                    lastSeq.updateAndGet(cur -> Math.max(cur, extractSequence(event.getEventId())));
                    if ("done".equals(event.getType())) {
                        sawDone.set(true);
                    }
                });

        Flux<RunEvent> untilDone = source.takeUntil(event -> "done".equals(event.getType()));

        int capMinutes = Math.max(1, streamMaxDurationMinutes);
        Flux<RunEvent> capped = untilDone.takeUntilOther(Mono.delay(Duration.ofMinutes(capMinutes)));

        Flux<RunEvent> timeoutTail = Flux.defer(() -> {
            if (sawDone.get()) {
                return Flux.empty();
            }
            log.warn("SSE stream exceeded max duration ({} min) for run {} — failing run with sse_stream_timeout",
                    capMinutes, runId);
            return publishErrorAndFail(runId, sessionId, "sse_stream_timeout",
                            "Run event stream exceeded the configured maximum duration; the connection was closed.")
                    .thenMany(replayFromRedis(runId, lastSeq.get()));
        });

        // share() — dataSse and keepAlive's takeUntilOther(runEvents.then()) must not double-subscribe
        // (would duplicate timeout handling / Redis publishes).
        Flux<RunEvent> runEvents = capped.concatWith(timeoutTail).share();

        Flux<ServerSentEvent<String>> dataSse = runEvents.map(event -> ServerSentEvent.<String>builder()
                .id(event.getEventId())
                .event(event.getType())
                .data(toJson(event))
                .build());

        if (heartbeatIntervalSeconds <= 0) {
            return dataSse
                    .doOnSubscribe(s -> log.debug("SSE stream opened for run {} (lastEventId={})", runId, lastEventId))
                    .doOnCancel(() -> log.debug("SSE client disconnected for run {}", runId));
        }

        Flux<ServerSentEvent<String>> keepAlive = Flux.interval(Duration.ofSeconds(heartbeatIntervalSeconds))
                .takeUntilOther(runEvents.then())
                .map(tick -> ServerSentEvent.<String>builder()
                        .comment("keepalive")
                        .build());

        return Flux.merge(dataSse, keepAlive)
                .doOnSubscribe(s -> log.debug("SSE stream opened for run {} (lastEventId={})", runId, lastEventId))
                .doOnCancel(() -> log.debug("SSE client disconnected for run {}", runId));
    }

    /**
     * Stream SSE events for a session. Subscribes to the session-level Redis Pub/Sub channel,
     * which receives a mirror of every run event published within this session.
     * This allows a single long-lived SSE connection to track all runs in the session
     * without reconnecting for each new run.
     */
    public Flux<ServerSentEvent<String>> streamSessionEvents(UUID sessionId) {
        String channel = SESSION_CHANNEL_PREFIX + sessionId;

        return redisTemplate.listenTo(ChannelTopic.of(channel))
                .map(ReactiveSubscription.Message::getMessage)
                .mapNotNull(this::deserializeEvent)
                .map(event -> ServerSentEvent.<String>builder()
                        .id(event.getEventId())
                        .event(event.getType())
                        .data(toJson(event))
                        .build())
                .doOnSubscribe(s -> log.debug("Session SSE stream opened for session {}", sessionId))
                .doOnCancel(() -> log.debug("Session SSE client disconnected for session {}", sessionId));
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

                        if ("waiting_for_input".equals(mappedType) || "hitl_request".equals(mappedType)
                                || "operation_authorization".equals(mappedType)) {
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
            // P3-016: LlmCallStepExecutor emits payload key "thinkingContent";
            // direct publishThinking() calls (simple chat path) use "content".
            // Accept both so neither path silently drops thinking text.
            case "thinking", "reasoning" -> {
                String tc = strVal(payload, "thinkingContent");
                runEvent.setContent(tc != null ? tc : strVal(payload, "content"));
            }
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
            // GAP-HITL-001 fix: populate all HITL fields for MCP tool authorization events
            // so the frontend can render "Approve tool operation?" with tool name, risk level,
            // description, and a resumeToken to call the consent endpoint.
            case "operation_authorization", "mcp.hitl.required" -> {
                runEvent.setInteractionType("OPERATION_AUTHORIZATION");
                runEvent.setInteractionId(resolveHitlInteractionId(payload));
                runEvent.setToolName(strVal(payload, "operation"));
                runEvent.setPrompt(strVal(payload, "description"));
                // Pass risk level and resumeToken via metadata map
                Map<String, Object> meta = new java.util.LinkedHashMap<>();
                String riskLevel = strVal(payload, "riskLevel");
                if (riskLevel != null) meta.put("riskLevel", riskLevel);
                Object rawMeta = payload.get("metadata");
                if (rawMeta instanceof Map<?,?> rawMap) {
                    rawMap.forEach((k, v) -> meta.put(k.toString(), v));
                }
                Object topConsent = payload.get("consentOptions");
                if (topConsent != null) {
                    meta.put("consentOptions", topConsent);
                }
                if (!meta.isEmpty()) runEvent.setArguments(meta);
            }
            case "skill.hitl.required", "subagent.hitl.required", "hitl.required", "task.hitl.required" -> {
                runEvent.setInteractionType("HITL_REQUEST");
                runEvent.setInteractionId(resolveHitlInteractionId(payload));
                runEvent.setToolName(strVal(payload, "operation"));
                runEvent.setPrompt(strVal(payload, "description"));
                Map<String, Object> meta = new java.util.LinkedHashMap<>();
                String riskLevel = strVal(payload, "riskLevel");
                if (riskLevel != null) meta.put("riskLevel", riskLevel);
                Object rawMeta = payload.get("metadata");
                if (rawMeta instanceof Map<?,?> rawMap) {
                    rawMap.forEach((k, v) -> meta.put(k.toString(), v));
                }
                Object topConsentSkill = payload.get("consentOptions");
                if (topConsentSkill != null) {
                    meta.put("consentOptions", topConsentSkill);
                }
                if (!meta.isEmpty()) runEvent.setArguments(meta);
            }
            case "task.hitl.resolved" -> {
                runEvent.setInteractionType("HITL_RESOLVED");
                String op = strVal(payload, "operation");
                String approved = strVal(payload, "approved");
                runEvent.setContent(op != null ? op + " approved=" + approved : approved);
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

    /**
     * P4-HITL fix: uses a configurable map for event type normalization.
     * Unmapped event types are passed through as-is.
     */
    private String mapEventType(String agentRuntimeType) {
        return eventTypeMap().getOrDefault(agentRuntimeType, agentRuntimeType);
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
            // step.llm.thinking is normalized to "thinking" by mapEventType() before reaching here
            case "thinking", "reasoning", "step.llm.thinking" -> DisplayMetadata.builder()
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
            case "mcp.hitl.required", "skill.hitl.required", "subagent.hitl.required", "task.hitl.required" ->
                    DisplayMetadata.builder()
                            .category("interaction").priority("important")
                            .summary("Approval required: " + strVal(payload, "operation")).build();
            case "task.hitl.resolved" -> DisplayMetadata.builder()
                    .category("interaction").priority("detail")
                    .summary("HITL resolved").build();
            case "mode_selected" -> DisplayMetadata.builder()
                    .category("execution").priority("important")
                    .summary("Execution mode: " + strVal(payload, "mode")).build();
            default -> DisplayMetadata.builder()
                    .category("execution").priority("detail")
                    .summary(strVal(payload, "message")).build();
        };
    }

    // ─────────────── Redis Operations ───────────────

    /**
     * Atomically increments and returns the next monotonic sequence number for a run.
     * The TTL on the seq key is set inside {@link #bufferAndPublish} when seq == 1.
     */
    private Mono<Long> nextSequence(UUID runId) {
        return redisTemplate.opsForValue()
                .increment(EVENT_SEQ_PREFIX + runId);
    }

    /**
     * Atomically writes the event to the sorted-set buffer and publishes it to the
     * run's Pub/Sub channel via a single Lua script (P3 fix).
     *
     * <p>Guarantee: any Pub/Sub subscriber that receives this event will find it in
     * the sorted set if it reconnects immediately afterward, because ZADD and PUBLISH
     * execute in the same server-side operation with no observable gap.
     *
     * <p>After the atomic core, the session-level mirror publish and the DB
     * last-event-id update run as fire-and-forget side effects.
     */
    private Mono<Void> bufferAndPublish(UUID runId, RunEvent event, long seq) {
        String json = toJson(event);
        String bufferKey = EVENT_BUFFER_PREFIX + runId;
        String channelKey = EVENT_CHANNEL_PREFIX + runId;
        String ttlStr = String.valueOf((long) eventBufferTtlMinutes * 60);

        // Atomic ZADD + EXPIRE + PUBLISH (Lua script, P3 fix)
        Mono<Void> atomic = redisTemplate
                .execute(ZADD_AND_PUBLISH_SCRIPT,
                        List.of(bufferKey, channelKey),
                        List.of(String.valueOf(seq), json, ttlStr))
                .then();

        // Expire the seq key on first event (fire-and-forget)
        if (seq == 1L) {
            atomic = atomic.then(
                    redisTemplate.expire(EVENT_SEQ_PREFIX + runId,
                            Duration.ofMinutes(eventBufferTtlMinutes)).then());
        }

        Mono<Void> publish = atomic
                .then(runService.updateLastEventId(runId, event.getEventId()))
                .then();

        // Mirror to the session-level channel so session SSE subscribers receive all run events
        if (event.getSessionId() != null) {
            String sessionChannelKey = SESSION_CHANNEL_PREFIX + event.getSessionId();
            publish = publish.then(redisTemplate.convertAndSend(sessionChannelKey, json).then());
        }

        return publish;
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
                .set(metaKey, sessionId.toString(), Duration.ofMinutes(eventBufferTtlMinutes))
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

    /**
     * Run input API requires a non-blank {@code interactionId}. Propagated HITL payloads
     * from Agent Runtime may omit it; derive from resume token or root task id when needed.
     */
    private String resolveHitlInteractionId(Map<String, Object> payload) {
        String id = strVal(payload, "interactionId");
        if (id != null && !id.isBlank()) {
            return id;
        }
        id = strVal(payload, "resumeToken");
        if (id != null && !id.isBlank()) {
            return id;
        }
        Object rawMeta = payload.get("metadata");
        if (rawMeta instanceof Map<?, ?> metaMap) {
            Object rt = metaMap.get("resumeToken");
            if (rt != null) {
                String s = String.valueOf(rt);
                if (!s.isBlank()) {
                    return s;
                }
            }
        }
        id = strVal(payload, "taskId");
        if (id != null && !id.isBlank()) {
            return id;
        }
        return null;
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
