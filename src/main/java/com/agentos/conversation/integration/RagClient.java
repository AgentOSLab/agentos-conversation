package com.agentos.conversation.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class RagClient {

    private final WebClient webClient;

    public RagClient(@Qualifier("ragWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<List<Map<String, Object>>> search(String query, List<String> namespaces,
                                                    int maxChunks, UUID tenantId) {
        Map<String, Object> body = Map.of(
                "query", query,
                "namespaces", namespaces,
                "maxChunks", maxChunks
        );

        return webClient.post()
                .uri("/api/v1/search")
                .header("X-Tenant-Id", tenantId.toString())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .doOnError(e -> log.error("RAG search failed: {}", e.getMessage()))
                .onErrorReturn(List.of());
    }
}
