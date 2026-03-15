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

/**
 * Auto-summarizes older conversation messages when a session exceeds
 * the sliding window. Uses a fast model via LLM Gateway for the
 * summarization call itself (minimal cost).
 *
 * Multi-instance safe: uses a Redis distributed lock to ensure only
 * one instance summarizes a given session at a time. Other instances
 * that trigger summarization concurrently will skip (lock-or-skip).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationSummarizer {

    private static final String LOCK_PREFIX = "lock:summarize:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    private final ConversationSessionService sessionService;
    private final LlmGatewayClient llmGatewayClient;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final SessionContextBuilder contextBuilder;

    @Value("${agentos.context.summarization-trigger:30}")
    private int summarizationTrigger;

    @Value("${agentos.context.default-window-size:20}")
    private int windowSize;

    public Mono<Void> summarizeIfNeeded(UUID sessionId, UUID tenantId) {
        return sessionService.getSession(tenantId, sessionId)
                .flatMap(session -> {
                    if (session.getMessageCount() < summarizationTrigger) {
                        return Mono.empty();
                    }

                    int excessMessages = session.getMessageCount() - windowSize;
                    if (excessMessages <= 0) {
                        return Mono.empty();
                    }

                    return acquireLock(sessionId)
                            .flatMap(acquired -> {
                                if (!acquired) {
                                    log.debug("Summarization lock not acquired for session {} — another instance is summarizing", sessionId);
                                    return Mono.empty();
                                }

                                return sessionService.getMessagesPaged(sessionId, excessMessages, 0)
                                        .flatMap(page -> {
                                            List<ConversationMessageEntity> oldMessages = page.getItems();
                                            if (oldMessages.isEmpty()) {
                                                return releaseLock(sessionId);
                                            }

                                            String existingSummary = session.getConversationSummary();
                                            return generateSummary(oldMessages, existingSummary)
                                                    .flatMap(newSummary ->
                                                            sessionService.updateConversationSummary(sessionId, newSummary)
                                                                    .then(contextBuilder.invalidateSessionCache(sessionId)))
                                                    .then(releaseLock(sessionId));
                                        })
                                        .onErrorResume(e -> {
                                            log.error("Summarization failed for session {}: {}", sessionId, e.getMessage());
                                            return releaseLock(sessionId);
                                        });
                            });
                })
                .then();
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
                                          String existingSummary) {
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
                        "Keep it under 500 words. Output plain text only."),
                Map.of("role", "user", "content", conversationText.toString())
        ));
        request.put("maxTokens", 800);
        request.put("temperature", 0.3);

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
