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
public class UserSystemClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public UserSystemClient(@Qualifier("userSystemWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<Map<String, Object>> getUserPermissions(UUID userId, UUID tenantId) {
        log.debug("User System call: endpoint=/api/v1/users/{}/permissions tenant={}", userId, tenantId);
        return webClient.get()
                .uri("/api/v1/users/{userId}/permissions", userId)
                .header("X-Tenant-Id", tenantId.toString())
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .doOnError(e -> log.error("User System call failed: endpoint=getUserPermissions error={}", e.getMessage()))
                .onErrorReturn(Map.of());
    }

    public Mono<Map<String, Object>> putUserCredential(UUID tenantId, UUID userId,
                                                        String scope, String key,
                                                        Map<String, Object> body) {
        log.debug("User System call: endpoint=putCredential scope={} key={}", scope, key);
        return webClient.put()
                .uri("/api/v1/me/credentials/{scope}/{key}", scope, key)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .doOnError(e -> log.error("Failed to proxy put credential: scope={}, key={}, error={}",
                        scope, key, e.getMessage()));
    }

    public Mono<List<Map<String, Object>>> listUserCredentials(UUID tenantId, UUID userId) {
        log.debug("User System call: endpoint=listCredentials userId={}", userId);
        return webClient.get()
                .uri("/api/v1/me/credentials")
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .bodyToMono(LIST_TYPE)
                .doOnError(e -> log.error("Failed to proxy list credentials: userId={}, error={}",
                        userId, e.getMessage()));
    }

    public Mono<Void> deleteUserCredential(UUID tenantId, UUID userId, String scope, String key) {
        log.debug("User System call: endpoint=deleteCredential scope={} key={}", scope, key);
        return webClient.delete()
                .uri("/api/v1/me/credentials/{scope}/{key}", scope, key)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .toBodilessEntity()
                .then()
                .doOnError(e -> log.error("Failed to proxy delete credential: scope={}, key={}, error={}",
                        scope, key, e.getMessage()));
    }
}
