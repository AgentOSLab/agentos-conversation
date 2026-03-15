package com.agentos.conversation.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class UserSystemClient {

    private final WebClient webClient;

    public UserSystemClient(@Qualifier("userSystemWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<Map<String, Object>> getUserPermissions(UUID userId, UUID tenantId) {
        return webClient.get()
                .uri("/api/v1/users/{userId}/permissions", userId)
                .header("X-Tenant-Id", tenantId.toString())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.error("Failed to get user permissions: {}", e.getMessage()))
                .onErrorReturn(Map.of());
    }
}
