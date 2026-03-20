package com.agentos.conversation.orchestration;

import com.agentos.conversation.integration.LlmGatewayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * GAP-RT-001 fix: LLM-based intent pre-classifier.
 *
 * Uses a fast/cheap LLM model to classify user messages into SIMPLE_CHAT or AGENT_TASK
 * before falling back to lexical scoring. This resolves the precision gap identified
 * in the audit — the pure lexical approach misclassifies complex requests that don't
 * contain action keywords, and over-routes simple questions containing tool nouns.
 *
 * Fallback behaviour: any error (network, timeout, parse) returns Optional.empty(),
 * causing IntentRouter to fall back to its existing lexical scoring logic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentClassifierService {

    private final LlmGatewayClient llmGatewayClient;

    @Value("${agentos.intent-classifier.model:claude-haiku-4-5-20251001}")
    private String classifierModel;

    @Value("${agentos.intent-classifier.timeout-ms:2000}")
    private long timeoutMs;

    @Value("${agentos.intent-classifier.enabled:true}")
    private boolean enabled;

    private static final String SYSTEM_PROMPT =
            "You are an intent classifier. Given a user message, reply with exactly one word:\n" +
            "- AGENT if the user wants to perform an action, use a tool, run a task, or complete a multi-step operation\n" +
            "- SIMPLE if the user is asking a question, chatting, or seeking information without needing tool use\n\n" +
            "Reply with ONLY 'AGENT' or 'SIMPLE'. No punctuation. No explanation.";

    /**
     * Classify a user message using a fast LLM call.
     *
     * @param userMessage the raw user message
     * @param tenantId    tenant context for LLM Gateway routing
     * @return Mono emitting Optional.of(RouteType) on success, or Optional.empty() on any failure
     */
    public Mono<Optional<IntentRouter.RouteType>> classify(String userMessage, UUID tenantId) {
        if (!enabled || userMessage == null || userMessage.isBlank()) {
            return Mono.just(Optional.empty());
        }

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userMessage)
        );

        Map<String, Object> requestBody = new java.util.LinkedHashMap<>();
        requestBody.put("messages", messages);
        requestBody.put("model", classifierModel);
        requestBody.put("max_tokens", 10);
        requestBody.put("temperature", 0.0);
        requestBody.put("tenantId", tenantId.toString());

        return llmGatewayClient.chat(requestBody)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(this::parseClassification)
                .doOnNext(result -> log.debug("LLM intent classification: '{}' → {}",
                        userMessage.length() > 60 ? userMessage.substring(0, 60) + "..." : userMessage,
                        result.orElse(null)))
                .onErrorResume(e -> {
                    log.warn("Intent classifier failed (falling back to lexical scoring): {}", e.getMessage());
                    return Mono.just(Optional.empty());
                });
    }

    @SuppressWarnings("unchecked")
    private Optional<IntentRouter.RouteType> parseClassification(Map<String, Object> response) {
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) return Optional.empty();

            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) return Optional.empty();

            String content = ((String) message.get("content")).trim().toUpperCase();
            if (content.startsWith("AGENT")) return Optional.of(IntentRouter.RouteType.AGENT_TASK);
            if (content.startsWith("SIMPLE")) return Optional.of(IntentRouter.RouteType.SIMPLE_CHAT);
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Failed to parse LLM classification response: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
