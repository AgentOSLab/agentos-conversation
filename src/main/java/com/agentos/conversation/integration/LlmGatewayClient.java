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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        return webClient.post()
                .uri("/api/v1/chat/completions")
                .header("X-Tenant-Id", tenantId.toString())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.error("LLM Gateway call failed: {}", e.getMessage()));
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
                .uri("/api/v1/chat/completions")
                .header("X-Tenant-Id", tenantId.toString())
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> chat(Map<String, Object> requestBody) {
        UUID tenantId = null;
        if (requestBody.containsKey("tenantId")) {
            tenantId = UUID.fromString(requestBody.get("tenantId").toString());
        }

        WebClient.RequestBodySpec spec = webClient.post()
                .uri("/api/v1/chat/completions");
        if (tenantId != null) {
            spec = spec.header("X-Tenant-Id", tenantId.toString());
        }

        return spec.bodyValue(requestBody)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.error("LLM Gateway call failed: {}", e.getMessage()));
    }
}
