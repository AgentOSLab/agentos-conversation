package com.agentos.conversation.context;

import com.agentos.conversation.integration.LlmGatewayClient;
import com.agentos.conversation.model.entity.ConversationMessageEntity;
import com.agentos.conversation.service.ConversationSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * Auto-summarizes older conversation messages when the session's estimated
 * token usage exceeds a configurable fraction of the model's context window.
 *
 * The trigger threshold and summary token budget are derived dynamically from
 * the model's contextWindow (queried via ModelContextService). System prompts
 * and pinned/highlighted messages are never touched by summarization.
 *
 * Multi-instance safe: uses a Redis distributed lock to ensure only one
 * instance summarizes a given session at a time (lock-or-skip).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationSummarizer {

    private static final String LOCK_PREFIX = "lock:summarize:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(90);

    private final ConversationSessionService sessionService;
    private final LlmGatewayClient llmGatewayClient;
    private final ModelContextService modelContextService;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final SessionContextBuilder contextBuilder;

    @Value("${agentos.context.summarization-threshold:0.80}")
    private double summarizationThreshold;

    @Value("${agentos.context.summary-token-ratio:0.07}")
    private double summaryTokenRatio;

    @Value("${agentos.context.summary-token-min:800}")
    private int summaryTokenMin;

    @Value("${agentos.context.summary-token-max:10000}")
    private int summaryTokenMax;

    @Value("${agentos.context.default-window-size:20}")
    private int windowSize;

    /**
     * Check whether summarization is needed based on estimated token usage
     * vs. the model's context window, and execute if so.
     *
     * @param onSummarized optional callback invoked when summarization completes
     */
    public Mono<Boolean> summarizeIfNeeded(UUID sessionId, UUID tenantId,
                                            Consumer<Boolean> onSummarized) {
        return modelContextService.getContextWindow(tenantId)
                .flatMap(contextWindow -> sessionService.getSession(tenantId, sessionId)
                        .flatMap(session -> {
                            int estimatedTokens = estimateSessionTokens(session.getMessageCount(),
                                    session.getConversationSummary());
                            int threshold = (int) (contextWindow * summarizationThreshold);

                            if (estimatedTokens < threshold) {
                                log.debug("Session {} tokens ~{} < threshold {} — skip summarization",
                                        sessionId, estimatedTokens, threshold);
                                return Mono.just(false);
                            }

                            int excessMessages = session.getMessageCount() - windowSize;
                            if (excessMessages <= 0) {
                                return Mono.just(false);
                            }

                            int summaryMaxTokens = computeSummaryMaxTokens(contextWindow);

                            return acquireLock(sessionId)
                                    .flatMap(acquired -> {
                                        if (!acquired) {
                                            log.debug("Summarization lock not acquired for session {}", sessionId);
                                            return Mono.just(false);
                                        }

                                        log.info("Summarization triggered for session {} (tokens ~{}, contextWindow={}, threshold={})",
                                                sessionId, estimatedTokens, contextWindow, threshold);

                                        return sessionService.getMessagesPaged(sessionId, excessMessages, 0)
                                                .flatMap(page -> {
                                                    List<ConversationMessageEntity> oldMessages = page.getItems();
                                                    if (oldMessages.isEmpty()) {
                                                        return releaseLock(sessionId).thenReturn(false);
                                                    }

                                                    String existingSummary = session.getConversationSummary();
                                                    return generateSummary(oldMessages, existingSummary, summaryMaxTokens)
                                                            .flatMap(newSummary ->
                                                                    sessionService.updateConversationSummary(sessionId, newSummary)
                                                                            .then(contextBuilder.invalidateSessionCache(sessionId)))
                                                            .then(releaseLock(sessionId))
                                                            .thenReturn(true)
                                                            .doOnNext(triggered -> {
                                                                if (onSummarized != null) onSummarized.accept(true);
                                                            });
                                                })
                                                .onErrorResume(e -> {
                                                    log.error("Summarization failed for session {}: {}", sessionId, e.getMessage());
                                                    return releaseLock(sessionId).thenReturn(false);
                                                });
                                    });
                        })
                )
                .defaultIfEmpty(false);
    }

    /** Backward-compatible overload without callback. */
    public Mono<Boolean> summarizeIfNeeded(UUID sessionId, UUID tenantId) {
        return summarizeIfNeeded(sessionId, tenantId, null);
    }

    int computeSummaryMaxTokens(int contextWindow) {
        int computed = (int) (contextWindow * summaryTokenRatio);
        return Math.max(summaryTokenMin, Math.min(computed, summaryTokenMax));
    }

    /**
     * Quick token estimate for a session based on message count and existing summary.
     * Uses a rough heuristic: average ~200 tokens per message.
     */
    private int estimateSessionTokens(int messageCount, String existingSummary) {
        int msgTokens = messageCount * 200;
        int summaryTokens = existingSummary != null ? existingSummary.length() / 4 : 0;
        return msgTokens + summaryTokens;
    }

    // ─────────────── Redis Distributed Lock ───────────────

    private Mono<Boolean> acquireLock(UUID sessionId) {
        String lockKey = LOCK_PREFIX + sessionId;
        return redisTemplate.opsForValue()
                .setIfAbsent(lockKey, UUID.randomUUID().toString(), LOCK_TTL)
                .defaultIfEmpty(false);
    }

    private Mono<Void> releaseLock(UUID sessionId) {
        return redisTemplate.delete(LOCK_PREFIX + sessionId).then();
    }

    // ─────────────── Summary Generation ───────────────

    @SuppressWarnings("unchecked")
    private Mono<String> generateSummary(List<ConversationMessageEntity> messages,
                                          String existingSummary,
                                          int summaryMaxTokens) {
        StringBuilder conversationText = new StringBuilder();

        if (existingSummary != null && !existingSummary.isBlank()) {
            conversationText.append("Previous summary:\n").append(existingSummary).append("\n\n");
        }

        conversationText.append("New messages to incorporate:\n");
        for (ConversationMessageEntity msg : messages) {
            if (isThinkingMessage(msg)) continue;

            conversationText.append("[").append(msg.getRole()).append("]: ");
            if (msg.getContent() != null) {
                String content = msg.getContent();
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }
                conversationText.append(content);
            }
            conversationText.append("\n");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("modelHint", "fast");
        request.put("messages", List.of(
                Map.of("role", "system", "content",
                        "You are a conversation summarizer. Produce a concise summary that preserves " +
                        "key decisions, task outcomes, user preferences, and important context. " +
                        "Output plain text only."),
                Map.of("role", "user", "content", conversationText.toString())
        ));
        request.put("maxTokens", summaryMaxTokens);
        request.put("temperature", 0.3);

        log.debug("Generating summary with maxTokens={}", summaryMaxTokens);

        return llmGatewayClient.chat(request)
                .map(response -> {
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
                    if (choices == null || choices.isEmpty()) return "Summary generation failed.";
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    return message != null ? String.valueOf(message.getOrDefault("content", "")) : "";
                })
                .onErrorResume(e -> {
                    log.warn("Conversation summarization failed: {}", e.getMessage());
                    return Mono.just(existingSummary != null ? existingSummary : "");
                });
    }

    private boolean isThinkingMessage(ConversationMessageEntity msg) {
        if (msg.getMetadata() == null) return false;
        return msg.getMetadata().contains("\"contentType\":\"thinking\"")
                || msg.getMetadata().contains("\"contentType\":\"reasoning\"");
    }
}
