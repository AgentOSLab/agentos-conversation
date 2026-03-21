package com.agentos.conversation.orchestration;

import com.agentos.conversation.context.ConversationSummarizer;
import com.agentos.conversation.context.SessionContextBuilder;
import com.agentos.conversation.context.SessionContextBuilder.AssembledContext;
import com.agentos.conversation.context.SessionContextBuilder.ContextRequest;
import com.agentos.conversation.integration.AgentRuntimeClient;
import com.agentos.conversation.integration.LlmGatewayClient;
import com.agentos.conversation.model.dto.*;
import com.agentos.conversation.model.entity.ConversationMessageEntity;
import com.agentos.conversation.model.entity.ConversationSessionEntity;
import com.agentos.conversation.model.entity.RunEntity;
import com.agentos.conversation.orchestration.IntentRouter.RouteDecision;
import com.agentos.conversation.orchestration.IntentRouter.RouteType;
import com.agentos.conversation.platform.ConversationPlatformToolConfig;
import com.agentos.conversation.service.ConversationSessionService;
import com.agentos.conversation.service.RunService;
import com.agentos.runtime.core.platform.PlatformToolContext;
import com.agentos.runtime.core.platform.PlatformToolDefinition;
import com.agentos.runtime.core.platform.PlatformToolDispatcher;
import com.agentos.runtime.core.platform.PlatformToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central orchestration logic built around the Run abstraction.
 * A user message creates a Run, which encapsulates the full lifecycle
 * regardless of internal routing (simple chat, agent task, workflow).
 *
 * Multi-instance safe: all event state flows through Redis via SseAggregator.
 * No in-memory sinks or counters.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageOrchestrator {

    private final ConversationSessionService sessionService;
    private final RunService runService;
    private final SessionContextBuilder contextBuilder;
    private final ConversationSummarizer summarizer;
    private final IntentRouter intentRouter;
    private final LlmGatewayClient llmGatewayClient;
    private final AgentRuntimeClient agentRuntimeClient;
    private final SseAggregator sseAggregator;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final PlatformToolDispatcher conversationPlatformToolDispatcher;
    private final ObjectMapper objectMapper;

    private static final int MAX_TOOL_ITERATIONS = 10;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Value("${agentos.run.task-run-ttl-hours:24}")
    private int taskRunTtlHours;

    @Value("${agentos.platform-tools.timeouts.dispatch:120s}")
    private Duration platformToolDispatchTimeout;

    /**
     * Create a Run from a user message, persist the message, route intent,
     * and kick off execution. Returns immediately with RunResponse.
     * The client subscribes to SSE events via GET /runs/{runId}/events.
     */
    public Mono<RunResponse> createAndExecuteRun(UUID tenantId, UUID userId,
                                                  UUID sessionId, CreateRunRequest request) {
        return sessionService.getSessionForUser(tenantId, sessionId, userId)
                .switchIfEmpty(Mono.error(new RuntimeException("Session not found: " + sessionId)))
                .flatMap(session -> {
                    ConversationMessageEntity userMsg = ConversationMessageEntity.builder()
                            .role("user")
                            .content(request.getContent())
                            .build();

                    return sessionService.appendMessage(sessionId, userMsg)
                            .flatMap(savedMsg ->
                                // GAP-RT-001 fix: async routing uses LLM pre-classifier with lexical fallback
                                intentRouter.route(request.getContent(), session, tenantId)
                                        .flatMap(route -> {
                                String routeType = mapRouteType(route.getRouteType());

                                return runService.createRun(sessionId, tenantId, userId, routeType, savedMsg.getId())
                                        .flatMap(run -> {
                                            log.info("Run {} created for session {} (route={}, interactionMode={})",
                                                    run.getId(), sessionId, routeType, route.getInteractionMode());

                                            kickOffExecution(run, session, route, request.getContent(), tenantId, userId);

                                            // Auto-generate session title on first user message (fire-and-forget)
                                            if (session.getMessageCount() == 0
                                                    && (session.getTitle() == null || session.getTitle().isBlank())) {
                                                generateSessionTitle(sessionId, tenantId, userId, request.getContent())
                                                        .subscribe(
                                                                v -> {},
                                                                e -> log.warn("Auto-title generation failed for session {}: {}",
                                                                        sessionId, e.getMessage())
                                                        );
                                            }

                                            return Mono.just(RunResponse.fromEntity(run));
                                        });
                                }));
                });
    }

    private void kickOffExecution(RunEntity run, ConversationSessionEntity session,
                                   RouteDecision route, String content,
                                   UUID tenantId, UUID userId) {
        if (route.getRouteType() == RouteType.SIMPLE_CHAT) {
            executeSimpleChat(run, session, content, tenantId)
                    .subscribe(
                            v -> {},
                            e -> {
                                log.error("Simple chat execution failed for run {}: {}", run.getId(), e.getMessage());
                                sseAggregator.publishErrorAndFail(run.getId(), run.getSessionId(),
                                        "simple_chat_error", e.getMessage()).subscribe();
                            }
                    );
        } else {
            executeAgentTask(run, session, route, content, tenantId, userId)
                    .subscribe(
                            v -> {},
                            e -> {
                                log.error("Agent task execution failed for run {}: {}", run.getId(), e.getMessage());
                                sseAggregator.publishErrorAndFail(run.getId(), run.getSessionId(),
                                        "agent_task_error", e.getMessage()).subscribe();
                            }
                    );
        }
    }

    /**
     * Simple chat: mark run as running, assemble context, call LLM with platform tools.
     *
     * <p>Supports a tool-call loop: if the LLM requests tool calls (web_search,
     * search_knowledge, etc.), we execute them and re-submit to the LLM.
     * The final text response is streamed token-by-token via SSE.
     *
     * <p>Tool-call loop uses non-streaming; only the final text response uses streaming.
     * This avoids the complexity of parsing tool_call deltas from a stream while still
     * giving the user a progressive text display for the answer.
     */
    @SuppressWarnings("unchecked")
    private Mono<Void> executeSimpleChat(RunEntity run, ConversationSessionEntity session,
                                          String content, UUID tenantId) {
        return runService.markRunning(run.getId())
                .then(Mono.defer(() -> {
                    ContextRequest ctxReq = ContextRequest.builder()
                            .sessionId(run.getSessionId())
                            .tenantId(tenantId)
                            .currentUserMessage(content)
                            .build();

                    return contextBuilder.buildContext(ctxReq)
                            .flatMap(ctx -> {
                                UUID runId = run.getId();
                                UUID sessionId = run.getSessionId();

                                Map<String, Object> ctxUsage = buildContextUsageMap(ctx, false);

                                // Build platform tool schemas for direct chat
                                List<Map<String, Object>> platformTools = buildDirectChatTools();

                                return sseAggregator.publishEvent(runId, sessionId, "run_started",
                                                RunEvent.DisplayMetadata.builder()
                                                        .category("execution").priority("important")
                                                        .summary("Run started").build(), null)
                                        .then(sseAggregator.publishContextUsage(runId, sessionId, ctxUsage))
                                        .then(sseAggregator.publishMessageStarted(runId, sessionId))
                                        .then(Mono.defer(() -> {
                                            if (platformTools.isEmpty()) {
                                                return streamPureLlmResponse(run, ctx, tenantId);
                                            }
                                            return executeToolCallLoop(run, ctx, platformTools, tenantId)
                                                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
                                        }))
                                        .onErrorResume(e -> {
                                            log.error("Simple chat failed for run {}: {}", runId, e.getMessage());
                                            return sseAggregator.publishErrorAndFail(
                                                    runId, sessionId, "simple_chat_error", e.getMessage());
                                        });
                            });
                }))
                .then(Mono.defer(() ->
                        summarizer.summarizeIfNeeded(run.getSessionId(), tenantId)))
                .then();
    }

    /**
     * Pure streaming path — no tools, direct LLM stream. Original simple chat behavior.
     */
    private Mono<Void> streamPureLlmResponse(RunEntity run, AssembledContext ctx, UUID tenantId) {
        UUID runId = run.getId();
        UUID sessionId = run.getSessionId();
        StringBuilder fullContent = new StringBuilder();
        AtomicInteger tokenIndex = new AtomicInteger(0);

        return llmGatewayClient.chatCompletionStream(ctx.getMessages(), ctx.getTools(), tenantId)
                .concatMap(chunk -> {
                    String tokenContent = extractStreamToken(chunk);
                    String thinkingContent = extractStreamThinking(chunk);

                    Mono<Void> ops = Mono.empty();
                    if (thinkingContent != null && !thinkingContent.isEmpty()) {
                        ops = ops.then(sseAggregator.publishThinking(runId, sessionId, thinkingContent));
                    }
                    if (tokenContent != null && !tokenContent.isEmpty()) {
                        fullContent.append(tokenContent);
                        ops = ops.then(sseAggregator.publishToken(
                                runId, sessionId, tokenContent, tokenIndex.getAndIncrement()));
                    }
                    return ops;
                })
                .then()
                .flatMap(v -> persistAndFinalize(run, fullContent.toString()));
    }

    /**
     * Tool-call loop: call LLM non-streaming → execute tool calls → repeat.
     * Final text response is emitted as token events for streaming UX.
     */
    private Mono<Void> executeToolCallLoop(RunEntity run, AssembledContext ctx,
                                            List<Map<String, Object>> platformTools, UUID tenantId) {
        UUID runId = run.getId();
        UUID sessionId = run.getSessionId();

        PlatformToolContext toolCtx = PlatformToolContext.builder()
                .taskId(runId)
                .tenantId(tenantId)
                .callerRuntime("conversation")
                .build();

        List<Map<String, Object>> messages = new ArrayList<>(ctx.getMessages());

        return Mono.fromCallable(() -> {
            String finalContent = null;

            for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
                Map<String, Object> response = llmGatewayClient
                        .chatCompletion(messages, platformTools, tenantId).block();

                if (response == null) {
                    return "";
                }

                String content = extractChatContent(response);
                List<Map<String, Object>> toolCalls = extractToolCalls(response);

                if (toolCalls == null || toolCalls.isEmpty()) {
                    finalContent = content;
                    break;
                }

                // Add assistant message with tool_calls to history
                Map<String, Object> assistantMsg = new LinkedHashMap<>();
                assistantMsg.put("role", "assistant");
                if (content != null) assistantMsg.put("content", content);
                assistantMsg.put("tool_calls", toolCalls);
                messages.add(assistantMsg);

                // Execute each tool call
                for (Map<String, Object> tc : toolCalls) {
                    String callId = (String) tc.getOrDefault("id", "");
                    Map<String, Object> function = tc.get("function") instanceof Map<?, ?>
                            ? (Map<String, Object>) tc.get("function") : Map.of();
                    String toolName = (String) function.getOrDefault("name", "");
                    String argsStr = (String) function.getOrDefault("arguments", "{}");

                    Map<String, Object> args;
                    try {
                        args = objectMapper.readValue(argsStr, MAP_TYPE);
                    } catch (Exception e) {
                        args = Map.of();
                    }

                    // Publish tool_call event for UI
                    sseAggregator.publishEvent(runId, sessionId, "tool_call",
                            RunEvent.DisplayMetadata.builder()
                                    .category("tool").priority("info")
                                    .summary("Calling " + toolName).build(),
                            Map.of("toolName", toolName, "callId", callId)).block();

                    String result = conversationPlatformToolDispatcher.dispatch(toolName, args, toolCtx)
                            .block(platformToolDispatchTimeout);

                    String resultPreview = result == null ? "" : result;
                    if (resultPreview.length() > 2048) {
                        resultPreview = resultPreview.substring(0, 2048) + "…";
                    }

                    // Publish tool_result event
                    sseAggregator.publishEvent(runId, sessionId, "tool_result",
                            RunEvent.DisplayMetadata.builder()
                                    .category("tool").priority("info")
                                    .summary(toolName + " completed").build(),
                            Map.of("toolName", toolName, "callId", callId, "resultPreview", resultPreview)).block();

                    messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", callId,
                            "content", result
                    ));
                }
            }

            return finalContent != null ? finalContent : "";
        }).flatMap(finalText -> {
            // Emit final text as token events for streaming UX
            AtomicInteger tokenIndex = new AtomicInteger(0);
            int chunkSize = 20;

            Mono<Void> emitTokens = Mono.empty();
            for (int i = 0; i < finalText.length(); i += chunkSize) {
                String chunk = finalText.substring(i, Math.min(i + chunkSize, finalText.length()));
                int idx = tokenIndex.getAndIncrement();
                emitTokens = emitTokens.then(
                        sseAggregator.publishToken(runId, sessionId, chunk, idx));
            }

            return emitTokens.then(persistAndFinalize(run, finalText));
        });
    }

    /**
     * Build OpenAI-format tool definitions for direct chat platform tools.
     */
    private List<Map<String, Object>> buildDirectChatTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (PlatformToolDefinition def : PlatformToolRegistry.enabledToolsInSubset(
                ConversationPlatformToolConfig.CONVERSATION_TOOLS, null)) {
            if (conversationPlatformToolDispatcher.hasHandler(def.name())) {
                tools.add(def.toOpenAiToolFormat());
            }
        }
        return tools;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractToolCalls(Map<String, Object> response) {
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                if (message != null && message.get("tool_calls") instanceof List<?> tc) {
                    return (List<Map<String, Object>>) tc;
                }
            }
        } catch (Exception e) {
            log.trace("Failed to extract tool_calls: {}", e.getMessage());
        }
        return null;
    }

    private Mono<Void> persistAndFinalize(RunEntity run, String assistantContent) {
        ConversationMessageEntity assistantMsg = ConversationMessageEntity.builder()
                .role("assistant")
                .content(assistantContent)
                .build();

        return sessionService.appendMessage(run.getSessionId(), assistantMsg)
                .flatMap(savedMsg ->
                        sseAggregator.publishRunFinalized(
                                run.getId(), run.getSessionId(), savedMsg.getId(),
                                assistantContent, null)
                        .then(runService.completeRun(run.getId(), savedMsg.getId(), null, null)));
    }

    @SuppressWarnings("unchecked")
    private String extractStreamToken(Map<String, Object> chunk) {
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                if (delta != null) {
                    return (String) delta.get("content");
                }
            }
        } catch (Exception e) {
            log.trace("Failed to extract stream token: {}", e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractStreamThinking(Map<String, Object> chunk) {
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                if (delta != null) {
                    return (String) delta.get("thinking");
                }
            }
        } catch (Exception e) {
            log.trace("Failed to extract stream thinking: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Agent task: pre-flight readiness check → mark run as running → create Task in Agent Runtime.
     *
     * Readiness gate (pre-execution):
     * If the session is bound to an agent, we check that all required MCP connections
     * are established and all declared Skills exist before creating the task.
     * If BLOCKING issues exist, the run is failed immediately with an "agent_not_ready"
     * SSE event containing the structured issue list — no LLM tokens are wasted.
     *
     * Events flow through Redis Pub/Sub → SseAggregator (multi-instance safe).
     */
    @SuppressWarnings("unchecked")
    private Mono<Void> executeAgentTask(RunEntity run, ConversationSessionEntity session,
                                         RouteDecision route, String content,
                                         UUID tenantId, UUID userId) {
        // Pre-flight: check agent readiness only when session is bound to an agent
        Mono<Boolean> readinessMono = Mono.just(true);
        if (session.getBoundEntityId() != null) {
            readinessMono = agentRuntimeClient
                    .checkReadiness(session.getBoundEntityId(), userId, tenantId)
                    .flatMap(result -> {
                        boolean ready = Boolean.TRUE.equals(result.get("ready"));
                        if (!ready) {
                            Object issues = result.getOrDefault("issues", List.of());
                            log.warn("Agent {} not ready for user {} in run {}: issues={}",
                                    session.getBoundEntityId(), userId, run.getId(), issues);

                            Map<String, Object> extra = new LinkedHashMap<>();
                            extra.put("issues", issues);
                            extra.put("agentId", session.getBoundEntityId().toString());

                            // Publish structured event so frontend can show setup guidance
                            return sseAggregator.publishEvent(
                                            run.getId(), run.getSessionId(),
                                            "agent_not_ready",
                                            RunEvent.DisplayMetadata.builder()
                                                    .category("setup")
                                                    .priority("critical")
                                                    .summary("Agent requires setup before it can run")
                                                    .build(),
                                            extra)
                                    .then(sseAggregator.publishErrorAndFail(
                                            run.getId(), run.getSessionId(),
                                            "AGENT_NOT_READY",
                                            "Some required resources are not configured. See issues for details."))
                                    .thenReturn(false);
                        }
                        return Mono.just(true);
                    });
        }

        return readinessMono.flatMap(ready -> {
            if (!ready) return Mono.empty(); // run already failed via readiness gate

            return runService.markRunning(run.getId())
                    .then(Mono.defer(() -> {
                        // P2-001 fix: do NOT set currentUserMessage here.
                        // SessionContextBuilder would append the user message to the assembled history,
                        // but LlmCallStepExecutor ALSO appends it via stepConfig "prompt" →
                        // the user turn would be duplicated in the LLM context.
                        // Conversation's job is to assemble the *prior* conversation history;
                        // Agent Runtime adds the current user instruction as the active turn.
                        ContextRequest contextRequest = ContextRequest.builder()
                                .sessionId(run.getSessionId())
                                .tenantId(tenantId)
                                .build();

                        return contextBuilder.buildContext(contextRequest)
                                .flatMap(assembledCtx -> {
                                    Map<String, Object> ctxUsage = buildContextUsageMap(assembledCtx, false);

                                    Map<String, Object> taskRequest = new LinkedHashMap<>();
                                    taskRequest.put("input", Map.of("content", content));
                                    taskRequest.put("sessionId", run.getSessionId().toString());
                                    taskRequest.put("interactionMode", route.getInteractionMode());
                                    taskRequest.put("conversationHistory", assembledCtx.getMessages());

                                    if (session.getBoundEntityId() != null) {
                                        taskRequest.put("agentId", session.getBoundEntityId().toString());
                                    }

                                    return sseAggregator.publishContextUsage(
                                                    run.getId(), run.getSessionId(), ctxUsage)
                                            .then(agentRuntimeClient.submitTask(taskRequest, tenantId, userId));
                                })
                                .flatMap(taskResponse -> {
                                    String taskIdStr = String.valueOf(taskResponse.get("taskId"));
                                    UUID taskId = UUID.fromString(taskIdStr);

                                    Duration taskTtl = Duration.ofHours(taskRunTtlHours);
                                    return runService.setTaskId(run.getId(), taskId)
                                            .then(redisTemplate.opsForValue().set(
                                                    "run:task:" + taskId, run.getId().toString(), taskTtl))
                                            // Store session + tenant mappings so RunStatusSyncService
                                            // can trigger summarization on task completion (Phase 5)
                                            .then(redisTemplate.opsForValue().set(
                                                    "run:task:" + taskId + ":session",
                                                    run.getSessionId().toString(), taskTtl))
                                            .then(redisTemplate.opsForValue().set(
                                                    "run:task:" + taskId + ":tenant",
                                                    tenantId.toString(), taskTtl))
                                            .then(sessionService.incrementTaskCount(run.getSessionId()))
                                            .then(sseAggregator.registerAgentTaskRun(
                                                    run.getId(), taskId, run.getSessionId(), tenantId));
                                });
                    }));
        });
    }

    /**
     * Stream SSE events for a Run. Handles reconnect via Last-Event-ID.
     * Any instance can serve this — all events come from Redis.
     */
    public Flux<ServerSentEvent<String>> streamRunEvents(UUID runId, UUID sessionId,
                                                          UUID tenantId, UUID userId,
                                                          String lastEventId) {
        return sseAggregator.streamRunEvents(runId, sessionId, tenantId, userId, lastEventId);
    }

    /**
     * Stream SSE events for a Session. Delivers a multiplexed stream of all run events
     * within this session so the client needs only one long-lived connection.
     * Any instance can serve this — events are mirrored to a Redis session channel.
     */
    public Flux<ServerSentEvent<String>> streamSessionEvents(UUID sessionId) {
        return sseAggregator.streamSessionEvents(sessionId);
    }

    /**
     * Cancel a running run. Propagates cancellation to Agent Runtime if applicable.
     * Works from any instance — cancel event published via Redis.
     */
    public Mono<RunResponse> cancelRun(UUID runId, UUID sessionId, UUID tenantId, UUID userId) {
        return runService.getRunForUser(runId, tenantId, userId)
                .filter(run -> sessionId.equals(run.getSessionId()))
                .switchIfEmpty(Mono.error(new RuntimeException("Run not found: " + runId)))
                .flatMap(run -> {
                    if ("completed".equals(run.getStatus()) || "cancelled".equals(run.getStatus())
                            || "failed".equals(run.getStatus())) {
                        return Mono.just(RunResponse.fromEntity(run));
                    }

                    Mono<Void> cancelTask = Mono.empty();
                    if (run.getTaskId() != null) {
                        cancelTask = agentRuntimeClient.cancelTask(run.getTaskId(), tenantId)
                                .onErrorResume(e -> {
                                    log.warn("Failed to cancel task {} for run {}: {}", run.getTaskId(), runId, e.getMessage());
                                    return Mono.empty();
                                });
                    }

                    return cancelTask
                            .then(runService.cancelRun(runId))
                            .then(sseAggregator.publishRunCancelled(runId))
                            .then(runService.getRunForUser(runId, tenantId, userId))
                            .map(RunResponse::fromEntity);
                });
    }

    /**
     * Submit human input for a HITL-paused run. Forwards to Agent Runtime.
     * Works from any instance — Agent Runtime receives input via HTTP,
     * then publishes events to Redis which any Conversation instance consumes.
     */
    public Mono<Void> submitHumanInput(UUID runId, UUID sessionId, UUID tenantId, UUID userId,
                                        SubmitRunInputRequest request) {
        return runService.getRunForUser(runId, tenantId, userId)
                .filter(run -> sessionId.equals(run.getSessionId()))
                .switchIfEmpty(Mono.error(new RuntimeException("Run not found: " + runId)))
                .flatMap(run -> {
                    if (run.getTaskId() == null) {
                        return Mono.error(new RuntimeException("Run " + runId + " has no associated task"));
                    }

                    Map<String, Object> input = new LinkedHashMap<>();
                    input.put("interactionId", request.getInteractionId());
                    if (request.getContent() != null) input.put("content", request.getContent());
                    if (request.getApproved() != null) input.put("approved", request.getApproved());
                    if (request.getSelectedOptions() != null) input.put("selectedOptions", request.getSelectedOptions());

                    return agentRuntimeClient.submitHumanInput(run.getTaskId(), input, tenantId, userId)
                            .then(runService.updateStatus(runId, "running"));
                });
    }

    /**
     * Retry a failed run by creating a new run with the same input message.
     */
    public Mono<RunResponse> retryRun(UUID runId, UUID sessionId, UUID tenantId, UUID userId) {
        return runService.getRunForUser(runId, tenantId, userId)
                .filter(run -> sessionId.equals(run.getSessionId()))
                .switchIfEmpty(Mono.error(new RuntimeException("Run not found: " + runId)))
                .flatMap(originalRun -> {
                    if (!"failed".equals(originalRun.getStatus())) {
                        return Mono.error(new RuntimeException("Only failed runs can be retried"));
                    }

                    return runService.createRun(sessionId, tenantId, userId,
                                    originalRun.getRouteType(), originalRun.getInputMessageId())
                            .flatMap(newRun -> sessionService.getSessionForUser(tenantId, sessionId, userId)
                                    .switchIfEmpty(Mono.error(new RuntimeException("Session not found: " + sessionId)))
                                    .flatMap(session -> {
                                        RouteDecision route = RouteDecision.builder()
                                                .routeType(fromRouteType(originalRun.getRouteType()))
                                                .interactionMode(session.getInteractionMode())
                                                .build();

                                        return sessionService.getMessageForSession(originalRun.getInputMessageId(), sessionId)
                                                .switchIfEmpty(Mono.error(new RuntimeException(
                                                        "Input message not found for session: " + sessionId)))
                                                .flatMap(msg -> {
                                                    kickOffExecution(newRun, session, route, msg.getContent(), tenantId, userId);
                                                    return Mono.just(RunResponse.fromEntity(newRun));
                                                });
                                    }));
                });
    }

    private Map<String, Object> buildContextUsageMap(AssembledContext ctx,
                                                      boolean summarizationTriggered) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("estimatedTokens", ctx.getEstimatedTokens());
        usage.put("maxContextTokens", ctx.getMaxContextTokens());
        usage.put("usagePercent", ctx.getContextUsagePercent());
        usage.put("messageCount", ctx.getMessageCountInWindow());
        usage.put("hasSummary", ctx.isHasSummary());
        usage.put("summarizationTriggered", summarizationTriggered);
        return usage;
    }

    private String mapRouteType(RouteType routeType) {
        return switch (routeType) {
            case SIMPLE_CHAT -> "simple_chat";
            case TOOL_CALL -> "agent_task";
            case AGENT_TASK -> "agent_task";
            case WORKFLOW -> "workflow";
        };
    }

    private RouteType fromRouteType(String routeType) {
        return switch (routeType) {
            case "simple_chat" -> RouteType.SIMPLE_CHAT;
            case "workflow" -> RouteType.WORKFLOW;
            default -> RouteType.AGENT_TASK;
        };
    }

    /**
     * Fire a non-streaming LLM call to generate a short title from the first user message,
     * then persist it on the session. Designed to be called fire-and-forget.
     */
    private Mono<Void> generateSessionTitle(UUID sessionId, UUID tenantId, UUID userId, String firstMessage) {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content",
                        "Generate a short, descriptive title (maximum 60 characters) for a conversation that starts with the following user message. Return only the title text, no quotes, no punctuation at the end."),
                Map.of("role", "user", "content", firstMessage)
        );

        return llmGatewayClient.chatCompletion(messages, null, tenantId)
                .flatMap(response -> {
                    String raw = extractChatContent(response);
                    if (raw != null && !raw.isBlank()) {
                        String title = raw.strip().replaceAll("^\"|\"$", "");
                        if (title.length() > 60) {
                            title = title.substring(0, 60);
                        }
                        log.debug("Auto-generated title for session {}: \"{}\"", sessionId, title);
                        return sessionService.updateTitle(tenantId, sessionId, userId, title).then();
                    }
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    log.warn("Auto-title generation failed for session {}: {}", sessionId, e.getMessage());
                    return Mono.empty();
                });
    }

    @SuppressWarnings("unchecked")
    private String extractChatContent(Map<String, Object> response) {
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                if (message != null) {
                    return (String) message.get("content");
                }
            }
        } catch (Exception e) {
            log.trace("Failed to extract chat content from LLM response: {}", e.getMessage());
        }
        return null;
    }
}
