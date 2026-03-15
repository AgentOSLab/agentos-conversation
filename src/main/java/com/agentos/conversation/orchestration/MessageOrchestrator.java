package com.agentos.conversation.orchestration;

import com.agentos.conversation.context.ConversationSummarizer;
import com.agentos.conversation.context.SessionContextBuilder;
import com.agentos.conversation.context.SessionContextBuilder.AssembledContext;
import com.agentos.conversation.context.SessionContextBuilder.ContextRequest;
import com.agentos.conversation.integration.AgentRuntimeClient;
import com.agentos.conversation.integration.LlmGatewayClient;
import com.agentos.conversation.model.entity.ConversationMessageEntity;
import com.agentos.conversation.model.entity.ConversationSessionEntity;
import com.agentos.conversation.orchestration.IntentRouter.RouteDecision;
import com.agentos.conversation.orchestration.IntentRouter.RouteType;
import com.agentos.conversation.service.ConversationSessionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Central orchestration logic. Receives a user message within a session,
 * determines the route (simple chat vs agent task vs workflow), and
 * executes accordingly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageOrchestrator {

    private final ConversationSessionService sessionService;
    private final SessionContextBuilder contextBuilder;
    private final ConversationSummarizer summarizer;
    private final IntentRouter intentRouter;
    private final LlmGatewayClient llmGatewayClient;
    private final AgentRuntimeClient agentRuntimeClient;
    private final SseAggregator sseAggregator;
    private final ObjectMapper objectMapper;

    /**
     * Process a user message: persist it, route to the right execution path,
     * persist the response, and return the result.
     */
    public Mono<Map<String, Object>> processMessage(UUID tenantId, UUID userId,
                                                      UUID sessionId, String content) {
        return sessionService.getSession(tenantId, sessionId)
                .switchIfEmpty(Mono.error(new RuntimeException("Session not found: " + sessionId)))
                .flatMap(session -> {
                    ConversationMessageEntity userMsg = ConversationMessageEntity.builder()
                            .role("user")
                            .content(content)
                            .build();

                    return sessionService.appendMessage(sessionId, userMsg)
                            .then(Mono.defer(() -> {
                                RouteDecision route = intentRouter.route(content, session);
                                log.info("Session {} routed to {} (interactionMode={})",
                                        sessionId, route.getRouteType(), route.getInteractionMode());

                                if (route.getRouteType() == RouteType.SIMPLE_CHAT) {
                                    return handleSimpleChat(tenantId, sessionId, content, session);
                                } else {
                                    return handleAgentTask(tenantId, userId, sessionId, content,
                                            session, route);
                                }
                            }))
                            .flatMap(result -> summarizer.summarizeIfNeeded(sessionId, tenantId)
                                    .thenReturn(result));
                });
    }

    /**
     * For simple chat: assemble context, call LLM directly, persist response.
     */
    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> handleSimpleChat(UUID tenantId, UUID sessionId,
                                                        String content,
                                                        ConversationSessionEntity session) {
        ContextRequest ctxReq = ContextRequest.builder()
                .sessionId(sessionId)
                .tenantId(tenantId)
                .currentUserMessage(content)
                .build();

        return contextBuilder.buildContext(ctxReq)
                .flatMap(ctx -> llmGatewayClient.chatCompletion(ctx.getMessages(), ctx.getTools(), tenantId))
                .flatMap(response -> {
                    String assistantContent = extractAssistantContent(response);

                    ConversationMessageEntity assistantMsg = ConversationMessageEntity.builder()
                            .role("assistant")
                            .content(assistantContent)
                            .build();

                    return sessionService.appendMessage(sessionId, assistantMsg)
                            .thenReturn(buildMessageResponse(sessionId, assistantContent, "simple_chat"));
                });
    }

    /**
     * For agent tasks / workflows: create a Task in Agent Runtime.
     */
    private Mono<Map<String, Object>> handleAgentTask(UUID tenantId, UUID userId,
                                                       UUID sessionId, String content,
                                                       ConversationSessionEntity session,
                                                       RouteDecision route) {
        Map<String, Object> taskRequest = new LinkedHashMap<>();
        taskRequest.put("input", content);
        taskRequest.put("sessionId", sessionId.toString());
        taskRequest.put("interactionMode", route.getInteractionMode());

        if (session.getBoundEntityId() != null) {
            taskRequest.put("agentId", session.getBoundEntityId().toString());
        }

        return agentRuntimeClient.submitTask(taskRequest, tenantId, userId)
                .flatMap(taskResponse -> {
                    String taskId = String.valueOf(taskResponse.get("taskId"));

                    return sessionService.incrementTaskCount(sessionId)
                            .thenReturn(buildTaskResponse(sessionId, taskId, route.getRouteType().name()));
                });
    }

    /**
     * Stream SSE events for an active task, enriched with session context.
     */
    public Flux<String> streamEvents(UUID taskId, UUID sessionId, UUID tenantId, UUID userId) {
        return sseAggregator.streamTaskAsConversationEvents(taskId, sessionId, tenantId, userId);
    }

    @SuppressWarnings("unchecked")
    private String extractAssistantContent(Map<String, Object> response) {
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                if (message != null) {
                    return String.valueOf(message.getOrDefault("content", ""));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract assistant content: {}", e.getMessage());
        }
        return "";
    }

    private Map<String, Object> buildMessageResponse(UUID sessionId, String content, String routeType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId.toString());
        result.put("routeType", routeType);
        result.put("content", content);
        return result;
    }

    private Map<String, Object> buildTaskResponse(UUID sessionId, String taskId, String routeType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId.toString());
        result.put("routeType", routeType);
        result.put("taskId", taskId);
        result.put("message", "Task created. Use SSE stream to follow execution.");
        return result;
    }
}
