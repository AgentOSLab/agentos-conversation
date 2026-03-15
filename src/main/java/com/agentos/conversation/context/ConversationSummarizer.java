package com.agentos.conversation.context;

import com.agentos.conversation.integration.LlmGatewayClient;
import com.agentos.conversation.model.entity.ConversationMessageEntity;
import com.agentos.conversation.service.ConversationSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Auto-summarizes older conversation messages when a session exceeds
 * the sliding window. Uses a fast model via LLM Gateway for the
 * summarization call itself (minimal cost).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationSummarizer {

    private final ConversationSessionService sessionService;
    private final LlmGatewayClient llmGatewayClient;

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

                    return sessionService.getMessagesPaged(sessionId, excessMessages, 0)
                            .flatMap(page -> {
                                List<ConversationMessageEntity> oldMessages = page.getItems();
                                if (oldMessages.isEmpty()) {
                                    return Mono.empty();
                                }

                                String existingSummary = session.getConversationSummary();
                                return generateSummary(oldMessages, existingSummary)
                                        .flatMap(newSummary ->
                                                sessionService.updateConversationSummary(sessionId, newSummary));
                            });
                })
                .then();
    }

    @SuppressWarnings("unchecked")
    private Mono<String> generateSummary(List<ConversationMessageEntity> messages,
                                          String existingSummary) {
        StringBuilder conversationText = new StringBuilder();

        if (existingSummary != null && !existingSummary.isBlank()) {
            conversationText.append("Previous summary:\n").append(existingSummary).append("\n\n");
        }

        conversationText.append("New messages to incorporate:\n");
        for (ConversationMessageEntity msg : messages) {
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
}
