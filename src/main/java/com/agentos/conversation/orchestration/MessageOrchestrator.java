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
import com.agentos.conversation.service.ConversationSessionService;
import com.agentos.conversation.service.RunService;
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

    @Value("${agentos.run.task-run-ttl-hours:24}")
    private int taskRunTtlHours;

    /**
     * Create a Run from a user message, persist the message, route intent,
     * and kick off execution. Returns immediately with RunResponse.
     * The client subscribes to SSE events via GET /runs/{runId}/events.
     */
    public Mono<RunResponse> createAndExecuteRun(UUID tenantId, UUID userId,
                                                  UUID sessionId, CreateRunRequest request) {
        return sessionService.getSession(tenantId, sessionId)
                .switchIfEmpty(Mono.error(new RuntimeException("Session not found: " + sessionId)))
                .flatMap(session -> {
                    ConversationMessageEntity userMsg = ConversationMessageEntity.builder()
                            .role("user")
                            .content(request.getContent())
                            .build();

                    return sessionService.appendMessage(sessionId, userMsg)
                            .flatMap(savedMsg -> {
                                RouteDecision route = intentRouter.route(request.getContent(), session);
                                String routeType = mapRouteType(route.getRouteType());

                                return runService.createRun(sessionId, tenantId, userId, routeType, savedMsg.getId())
                                        .flatMap(run -> {
                                            log.info("Run {} created for session {} (route={}, interactionMode={})",
                                                    run.getId(), sessionId, routeType, route.getInteractionMode());

                                            kickOffExecution(run, session, route, request.getContent(), tenantId, userId);

                                            return Mono.just(RunResponse.fromEntity(run));
                                        });
                            });
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
     * Simple chat: mark run as running, assemble context, stream LLM response
     * token-by-token via Redis-backed SSE, persist final assistant message, complete run.
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

                                return sseAggregator.publishEvent(runId, sessionId, "run_started",
                                                RunEvent.DisplayMetadata.builder()
                                                        .category("execution").priority("important")
                                                        .summary("Run started").build(), null)
                                        .then(sseAggregator.publishContextUsage(runId, sessionId, ctxUsage))
                                        .then(sseAggregator.publishMessageStarted(runId, sessionId))
                                        .then(Mono.defer(() -> {
                                            StringBuilder fullContent = new StringBuilder();
                                            AtomicInteger tokenIndex = new AtomicInteger(0);

                                            return llmGatewayClient.chatCompletionStream(
                                                            ctx.getMessages(), ctx.getTools(), tenantId)
                                                    .concatMap(chunk -> {
                                                        String tokenContent = extractStreamToken(chunk);
                                                        String thinkingContent = extractStreamThinking(chunk);

                                                        Mono<Void> ops = Mono.empty();
                                                        if (thinkingContent != null && !thinkingContent.isEmpty()) {
                                                            ops = ops.then(sseAggregator.publishThinking(
                                                                    runId, sessionId, thinkingContent));
                                                        }
                                                        if (tokenContent != null && !tokenContent.isEmpty()) {
                                                            fullContent.append(tokenContent);
                                                            ops = ops.then(sseAggregator.publishToken(
                                                                    runId, sessionId, tokenContent,
                                                                    tokenIndex.getAndIncrement()));
                                                        }
                                                        return ops;
                                                    })
                                                    .then()
                                                    .flatMap(v -> persistAndFinalize(run, fullContent.toString()))
                                                    .onErrorResume(e -> {
                                                        log.error("LLM streaming failed for run {}: {}", runId, e.getMessage());
                                                        return sseAggregator.publishErrorAndFail(
                                                                runId, sessionId, "llm_streaming_error", e.getMessage());
                                                    });
                                        }));
                            });
                }))
                .then(Mono.defer(() ->
                        summarizer.summarizeIfNeeded(run.getSessionId(), tenantId)))
                .then();
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

                                    return runService.setTaskId(run.getId(), taskId)
                                            .then(redisTemplate.opsForValue().set(
                                                    "run:task:" + taskId, run.getId().toString(),
                                                    Duration.ofHours(taskRunTtlHours)))
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
     * Cancel a running run. Propagates cancellation to Agent Runtime if applicable.
     * Works from any instance — cancel event published via Redis.
     */
    public Mono<RunResponse> cancelRun(UUID runId, UUID tenantId) {
        return runService.getRun(runId, tenantId)
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
                            .then(runService.getRun(runId, tenantId))
                            .map(RunResponse::fromEntity);
                });
    }

    /**
     * Submit human input for a HITL-paused run. Forwards to Agent Runtime.
     * Works from any instance — Agent Runtime receives input via HTTP,
     * then publishes events to Redis which any Conversation instance consumes.
     */
    public Mono<Void> submitHumanInput(UUID runId, UUID tenantId, UUID userId,
                                        SubmitRunInputRequest request) {
        return runService.getRun(runId, tenantId)
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
        return runService.getRun(runId, tenantId)
                .switchIfEmpty(Mono.error(new RuntimeException("Run not found: " + runId)))
                .flatMap(originalRun -> {
                    if (!"failed".equals(originalRun.getStatus())) {
                        return Mono.error(new RuntimeException("Only failed runs can be retried"));
                    }

                    return runService.createRun(sessionId, tenantId, userId,
                                    originalRun.getRouteType(), originalRun.getInputMessageId())
                            .flatMap(newRun -> sessionService.getSession(tenantId, sessionId)
                                    .flatMap(session -> {
                                        RouteDecision route = RouteDecision.builder()
                                                .routeType(fromRouteType(originalRun.getRouteType()))
                                                .interactionMode(session.getInteractionMode())
                                                .build();

                                        return sessionService.getMessageById(originalRun.getInputMessageId())
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
}
