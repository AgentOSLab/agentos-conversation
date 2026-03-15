package com.agentos.conversation.context;

import com.agentos.conversation.model.entity.ConversationMessageEntity;
import com.agentos.conversation.model.entity.ConversationSessionEntity;
import com.agentos.conversation.service.ConversationSessionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Assembles the LLM context for a conversation turn following the
 * prompt-caching-optimized structure:
 *
 *   CACHED PREFIX (stable across turns):
 *     system_prompt + agent_persona + execution_mode_rules + tool_definitions
 *     + rag_namespace_schemas + [cache_control: ephemeral]
 *
 *   DYNAMIC SUFFIX (changes each turn):
 *     conversation_summary (if session > window) + recent_messages
 *     + pinned_outputs + rag_results + current_user_input
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionContextBuilder {

    private final ConversationSessionService sessionService;
    private final ObjectMapper objectMapper;

    @Value("${agentos.context.default-window-size:20}")
    private int defaultWindowSize;

    @Value("${agentos.context.max-token-budget:100000}")
    private int maxTokenBudget;

    @Value("${agentos.context.pinned-messages-limit:10}")
    private int pinnedMessagesLimit;

    public Mono<AssembledContext> buildContext(ContextRequest request) {
        UUID sessionId = request.getSessionId();
        if (sessionId == null) {
            return Mono.just(buildStandaloneContext(request));
        }

        return sessionService.getSession(request.getTenantId(), sessionId)
                .flatMap(session -> assembleFromSession(session, request))
                .defaultIfEmpty(buildStandaloneContext(request));
    }

    private Mono<AssembledContext> assembleFromSession(ConversationSessionEntity session,
                                                       ContextRequest request) {
        Mono<List<ConversationMessageEntity>> recentMono =
                sessionService.getRecentMessages(session.getId(), defaultWindowSize);

        Mono<List<ConversationMessageEntity>> pinnedMono =
                sessionService.getPinnedMessages(session.getId());

        return Mono.zip(recentMono, pinnedMono)
                .map(tuple -> {
                    List<ConversationMessageEntity> recentMessages = tuple.getT1();
                    List<ConversationMessageEntity> pinnedMessages = tuple.getT2();

                    List<Map<String, Object>> messages = new ArrayList<>();
                    int tokenEstimate = 0;

                    Map<String, Object> systemMessage = buildSystemMessage(request, session);
                    systemMessage.put("cache_control", Map.of("type", "ephemeral"));
                    messages.add(systemMessage);
                    tokenEstimate += estimateTokens(systemMessage);

                    if (session.getConversationSummary() != null && !session.getConversationSummary().isBlank()) {
                        Map<String, Object> summaryMsg = Map.of(
                                "role", "system",
                                "content", "Previous conversation summary:\n" + session.getConversationSummary()
                        );
                        messages.add(summaryMsg);
                        tokenEstimate += estimateTokens(summaryMsg);
                    }

                    Set<UUID> pinnedIds = new HashSet<>();
                    for (ConversationMessageEntity pinned : pinnedMessages) {
                        if (tokenEstimate >= maxTokenBudget) break;
                        Map<String, Object> msg = messageToMap(pinned);
                        messages.add(msg);
                        pinnedIds.add(pinned.getId());
                        tokenEstimate += estimateTokens(msg);
                    }

                    for (ConversationMessageEntity recent : recentMessages) {
                        if (pinnedIds.contains(recent.getId())) continue;
                        if (tokenEstimate >= maxTokenBudget) break;
                        Map<String, Object> msg = messageToMap(recent);
                        messages.add(msg);
                        tokenEstimate += estimateTokens(msg);
                    }

                    if (request.getRagResults() != null && !request.getRagResults().isEmpty()) {
                        Map<String, Object> ragMsg = Map.of(
                                "role", "system",
                                "content", "Retrieved knowledge:\n" + formatRagResults(request.getRagResults())
                        );
                        messages.add(ragMsg);
                        tokenEstimate += estimateTokens(ragMsg);
                    }

                    if (request.getCurrentUserMessage() != null) {
                        messages.add(Map.of(
                                "role", "user",
                                "content", request.getCurrentUserMessage()
                        ));
                    }

                    return AssembledContext.builder()
                            .messages(messages)
                            .tools(request.getTools())
                            .estimatedTokens(tokenEstimate)
                            .sessionId(session.getId())
                            .messageCountInWindow(recentMessages.size())
                            .hasSummary(session.getConversationSummary() != null)
                            .build();
                });
    }

    private AssembledContext buildStandaloneContext(ContextRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();

        Map<String, Object> systemMessage = buildSystemMessage(request, null);
        systemMessage.put("cache_control", Map.of("type", "ephemeral"));
        messages.add(systemMessage);

        if (request.getConversationHistory() != null) {
            messages.addAll(request.getConversationHistory());
        }

        if (request.getCurrentUserMessage() != null) {
            messages.add(Map.of("role", "user", "content", request.getCurrentUserMessage()));
        }

        return AssembledContext.builder()
                .messages(messages)
                .tools(request.getTools())
                .estimatedTokens(estimateTokens(messages))
                .messageCountInWindow(messages.size())
                .hasSummary(false)
                .build();
    }

    private Map<String, Object> buildSystemMessage(ContextRequest request,
                                                    ConversationSessionEntity session) {
        StringBuilder sb = new StringBuilder();

        if (request.getAgentPersona() != null) {
            sb.append(request.getAgentPersona()).append("\n\n");
        }
        if (request.getExecutionModeRules() != null) {
            sb.append(request.getExecutionModeRules()).append("\n\n");
        }
        if (session != null && session.getMcpToolConfig() != null) {
            sb.append("Active MCP tools: ").append(session.getMcpToolConfig()).append("\n\n");
        }

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "system");
        msg.put("content", sb.toString().trim());
        return msg;
    }

    private Map<String, Object> messageToMap(ConversationMessageEntity entity) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", entity.getRole());
        if (entity.getContent() != null) {
            msg.put("content", entity.getContent());
        }
        if (entity.getContentBlocks() != null) {
            try {
                msg.put("content_blocks", objectMapper.readValue(entity.getContentBlocks(),
                        new TypeReference<List<Map<String, Object>>>() {}));
            } catch (JsonProcessingException ignored) {}
        }
        if (entity.getToolCalls() != null) {
            try {
                msg.put("tool_calls", objectMapper.readValue(entity.getToolCalls(),
                        new TypeReference<List<Map<String, Object>>>() {}));
            } catch (JsonProcessingException ignored) {}
        }
        if (entity.getToolCallId() != null) {
            msg.put("tool_call_id", entity.getToolCallId());
        }
        return msg;
    }

    private String formatRagResults(List<Map<String, Object>> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> chunk = results.get(i);
            sb.append("[").append(i + 1).append("] ");
            sb.append(chunk.getOrDefault("content", "")).append("\n");
        }
        return sb.toString();
    }

    private int estimateTokens(Map<String, Object> message) {
        String content = String.valueOf(message.getOrDefault("content", ""));
        return content.length() / 4;
    }

    private int estimateTokens(List<Map<String, Object>> messages) {
        return messages.stream().mapToInt(this::estimateTokens).sum();
    }

    @Data
    @Builder
    public static class ContextRequest {
        private UUID sessionId;
        private UUID tenantId;
        private String agentPersona;
        private String executionModeRules;
        private List<Map<String, Object>> tools;
        private List<Map<String, Object>> conversationHistory;
        private List<Map<String, Object>> ragResults;
        private String currentUserMessage;
    }

    @Data
    @Builder
    public static class AssembledContext {
        private List<Map<String, Object>> messages;
        private List<Map<String, Object>> tools;
        private int estimatedTokens;
        private UUID sessionId;
        private int messageCountInWindow;
        private boolean hasSummary;
    }
}
