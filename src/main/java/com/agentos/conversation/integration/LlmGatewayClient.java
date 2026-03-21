package com.agentos.conversation.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

@Slf4j
@Component
public class LlmGatewayClient {

    private final WebClient webClient;

    public LlmGatewayClient(@Qualifier("llmGatewayWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<Map<String, Object>> chatCompletion(List<Map<String, Object>> messages,
                                                     List<Map<String, Object>> tools,
                                                     UUID tenantId) {
        log.debug("LLM Gateway call: endpoint=/api/internal/v1/chat/completions tenant={} toolCount={}",
                tenantId, tools != null ? tools.size() : 0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        return webClient.post()
                .uri("/api/internal/v1/chat/completions")
                .header("X-Tenant-Id", tenantId.toString())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.error("LLM Gateway call failed: error={}", e.getMessage()));
    }

    public Flux<Map<String, Object>> chatCompletionStream(List<Map<String, Object>> messages,
                                                           List<Map<String, Object>> tools,
                                                           UUID tenantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", messages);
        body.put("stream", true);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        return webClient.post()
                .uri("/api/internal/v1/chat/completions")
                .header("X-Tenant-Id", tenantId.toString())
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    public Mono<List<Map<String, Object>>> listModels(UUID tenantId) {
        return webClient.get()
                .uri("/api/v1/models")
                .header("X-Tenant-Id", tenantId.toString())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> {
                    Object models = response.get("models");
                    if (models instanceof List<?> list) {
                        return (List<Map<String, Object>>) (List<?>) list;
                    }
                    return List.<Map<String, Object>>of();
                })
                .onErrorResume(e -> {
                    log.warn("Failed to fetch models from LLM Gateway: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    public Mono<Map<String, Object>> chat(Map<String, Object> requestBody) {
        if (!requestBody.containsKey("tenantId")) {
            return Mono.error(new IllegalArgumentException("tenantId is required for LLM Gateway chat"));
        }
        UUID tenantId = UUID.fromString(requestBody.get("tenantId").toString());

        log.debug("LLM Gateway chat call: tenant={}", tenantId);
        WebClient.RequestBodySpec spec = webClient.post()
                .uri("/api/internal/v1/chat/completions")
                .header("X-Tenant-Id", tenantId.toString());

        return spec.bodyValue(requestBody)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.error("LLM Gateway call failed: error={}", e.getMessage()));
    }
}
