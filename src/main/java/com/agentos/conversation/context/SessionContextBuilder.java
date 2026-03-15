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
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

/**
 * Assembles the LLM context for a conversation turn.
 *
 * Three-level caching strategy:
 *   L1: JVM heap — session entity (within request scope, not persisted across requests)
 *   L2: Redis — recent messages, pinned messages, conversation summary (TTL-based)
 *   L3: PostgreSQL — authoritative source
 *
 * Cache invalidation: writes through ConversationSessionService invalidate
 * the relevant Redis keys. Read path: Redis → DB fallback with write-through.
 *
 * Context structure (prompt-caching-optimized):
 *   CACHED PREFIX (stable across turns):
 *     system_prompt + agent_persona + execution_mode_rules + tool_definitions
 *     + rag_namespace_schemas + [cache_control: ephemeral]
 *   DYNAMIC SUFFIX (changes each turn):
 *     conversation_summary + recent_messages + pinned_outputs
 *     + rag_results + current_user_input
 *
 * Token-based windowing: messages are added to the context window until the
 * estimated token count reaches maxTokenBudget. Thinking/reasoning messages
 * and verbose tool outputs are excluded or compressed to save context space.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionContextBuilder {

    private static final String CACHE_PREFIX = "ctx:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Set<String> EXCLUDED_CONTENT_TYPES = Set.of("thinking", "reasoning");

    private final ConversationSessionService sessionService;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${agentos.context.default-window-size:20}")
    private int defaultWindowSize;

    @Value("${agentos.context.max-token-budget:100000}")
    private int maxTokenBudget;

    @Value("${agentos.context.pinned-messages-limit:10}")
    private int pinnedMessagesLimit;

    @Value("${agentos.context.tool-output-max-chars:2000}")
    private int toolOutputMaxChars;

    public Mono<AssembledContext> buildContext(ContextRequest request) {
        UUID sessionId = request.getSessionId();
        if (sessionId == null) {
            return Mono.just(buildStandaloneContext(request));
        }

        return sessionService.getSession(request.getTenantId(), sessionId)
                .flatMap(session -> assembleFromSession(session, request))
                .defaultIfEmpty(buildStandaloneContext(request));
    }

    /**
     * Invalidate all cached context data for a session.
     * Call after appending messages, updating summary, or changing pinned messages.
     */
    public Mono<Void> invalidateSessionCache(UUID sessionId) {
        return redisTemplate.delete(
                        CACHE_PREFIX + "recent:" + sessionId,
                        CACHE_PREFIX + "pinned:" + sessionId,
                        CACHE_PREFIX + "summary:" + sessionId)
                .then();
    }

    private Mono<AssembledContext> assembleFromSession(ConversationSessionEntity session,
                                                       ContextRequest request) {
        UUID sessionId = session.getId();

        Mono<List<ConversationMessageEntity>> recentMono = getCachedRecentMessages(sessionId);
        Mono<List<ConversationMessageEntity>> pinnedMono = getCachedPinnedMessages(sessionId);
        Mono<String> summaryMono = getCachedSummary(sessionId, session.getConversationSummary());

        return Mono.zip(recentMono, pinnedMono, summaryMono)
                .map(tuple -> {
                    List<ConversationMessageEntity> recentMessages = tuple.getT1();
                    List<ConversationMessageEntity> pinnedMessages = tuple.getT2();
                    String summary = tuple.getT3();

                    List<Map<String, Object>> messages = new ArrayList<>();
                    int tokenEstimate = 0;

                    Map<String, Object> systemMessage = buildSystemMessage(request, session);
                    systemMessage.put("cache_control", Map.of("type", "ephemeral"));
                    messages.add(systemMessage);
                    tokenEstimate += estimateTokens(systemMessage);

                    if (summary != null && !summary.isBlank()) {
                        Map<String, Object> summaryMsg = Map.of(
                                "role", "system",
                                "content", "Previous conversation summary:\n" + summary
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
                        if (shouldExcludeFromContext(recent)) continue;

                        Map<String, Object> msg = messageToMap(recent);
                        compressToolOutputIfNeeded(msg);
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
                            .hasSummary(summary != null && !summary.isBlank())
                            .build();
                });
    }

    // ─────────────── Redis L2 Cache ───────────────

    private Mono<List<ConversationMessageEntity>> getCachedRecentMessages(UUID sessionId) {
        String key = CACHE_PREFIX + "recent:" + sessionId;

        return redisTemplate.opsForValue().get(key)
                .flatMap(json -> {
                    try {
                        List<ConversationMessageEntity> cached = objectMapper.readValue(json,
                                new TypeReference<>() {});
                        return Mono.just(cached);
                    } catch (JsonProcessingException e) {
                        log.debug("Cache deserialization miss for recent messages, fetching from DB");
                        return Mono.<List<ConversationMessageEntity>>empty();
                    }
                })
                .switchIfEmpty(
                        sessionService.getRecentMessages(sessionId, defaultWindowSize)
                                .flatMap(messages -> cacheAndReturn(key, messages))
                );
    }

    private Mono<List<ConversationMessageEntity>> getCachedPinnedMessages(UUID sessionId) {
        String key = CACHE_PREFIX + "pinned:" + sessionId;

        return redisTemplate.opsForValue().get(key)
                .flatMap(json -> {
                    try {
                        List<ConversationMessageEntity> cached = objectMapper.readValue(json,
                                new TypeReference<>() {});
                        return Mono.just(cached);
                    } catch (JsonProcessingException e) {
                        log.debug("Cache deserialization miss for pinned messages, fetching from DB");
                        return Mono.<List<ConversationMessageEntity>>empty();
                    }
                })
                .switchIfEmpty(
                        sessionService.getPinnedMessages(sessionId)
                                .flatMap(messages -> cacheAndReturn(key, messages))
                );
    }

    private Mono<String> getCachedSummary(UUID sessionId, String sessionSummary) {
        if (sessionSummary != null && !sessionSummary.isBlank()) {
            return Mono.just(sessionSummary);
        }

        String key = CACHE_PREFIX + "summary:" + sessionId;
        return redisTemplate.opsForValue().get(key)
                .defaultIfEmpty("");
    }

    private <T> Mono<T> cacheAndReturn(String key, T value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            return redisTemplate.opsForValue().set(key, json, CACHE_TTL)
                    .thenReturn(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to cache value for key {}: {}", key, e.getMessage());
            return Mono.just(value);
        }
    }

    // ─────────────── Context Filtering & Compression ───────────────

    /**
     * Exclude thinking/reasoning messages from the context window.
     * These are valuable for the user's visibility but waste LLM context space.
     */
    private boolean shouldExcludeFromContext(ConversationMessageEntity message) {
        if (message.getMetadata() == null) return false;
        try {
            Map<String, Object> meta = objectMapper.readValue(message.getMetadata(),
                    new TypeReference<>() {});
            String contentType = (String) meta.get("contentType");
            return contentType != null && EXCLUDED_CONTENT_TYPES.contains(contentType);
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * Compress tool output content if it exceeds the threshold.
     * Keeps the first and last portions to preserve intent and result.
     */
    @SuppressWarnings("unchecked")
    private void compressToolOutputIfNeeded(Map<String, Object> msg) {
        if (!"tool".equals(msg.get("role"))) return;
        Object content = msg.get("content");
        if (content instanceof String s && s.length() > toolOutputMaxChars) {
            int halfLimit = toolOutputMaxChars / 2;
            String compressed = s.substring(0, halfLimit)
                    + "\n...[truncated " + (s.length() - toolOutputMaxChars) + " chars]...\n"
                    + s.substring(s.length() - halfLimit);
            msg.put("content", compressed);
        }
    }

    // ─────────────── Context Building Helpers ───────────────

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
