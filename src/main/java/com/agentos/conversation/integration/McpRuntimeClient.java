package com.agentos.conversation.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Internal HTTP client for MCP Runtime connection management.
 *
 * <p>MCP Runtime's user-facing connection endpoints are internal-only
 * ({@code /api/internal/v1/mcp-connections/**}). All external client access
 * is proxied through Conversation's {@code McpConnectionProxyController}.
 */
@Slf4j
@Component
public class McpRuntimeClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public McpRuntimeClient(@Qualifier("mcpRuntimeWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<Map<String, Object>> listMyConnections(UUID tenantId, UUID userId,
                                                        int pageSize, String pageToken,
                                                        String status, UUID activationId) {
        UriComponentsBuilder uri = UriComponentsBuilder
                .fromPath("/api/internal/v1/mcp-connections/me")
                .queryParam("pageSize", pageSize);
        if (pageToken != null) uri.queryParam("pageToken", pageToken);
        if (status != null)    uri.queryParam("status", status);
        if (activationId != null) uri.queryParam("activationId", activationId.toString());

        return webClient.get()
                .uri(uri.toUriString())
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .doOnError(e -> log.error("McpRuntime listMyConnections failed: {}", e.getMessage()));
    }

    public Mono<Map<String, Object>> beginConnection(UUID activationId, UUID tenantId, UUID userId,
                                                      Object requestBody) {
        return webClient.post()
                .uri("/api/internal/v1/mcp-connections/{activationId}/begin", activationId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .bodyValue(requestBody != null ? requestBody : Map.of())
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .doOnError(e -> log.error("McpRuntime beginConnection failed activationId={}: {}", activationId, e.getMessage()));
    }

    public Mono<Map<String, Object>> updateUserConfig(UUID connectionId, UUID tenantId, UUID userId,
                                                       Object requestBody) {
        return webClient.patch()
                .uri("/api/internal/v1/mcp-connections/{connectionId}/user-config", connectionId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .doOnError(e -> log.error("McpRuntime updateUserConfig failed connectionId={}: {}", connectionId, e.getMessage()));
    }

    public Mono<Map<String, Object>> setUserCredential(UUID connectionId, UUID tenantId, UUID userId,
                                                        Object requestBody) {
        return webClient.post()
                .uri("/api/internal/v1/mcp-connections/{connectionId}/set-credential", connectionId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .doOnError(e -> log.error("McpRuntime setUserCredential failed connectionId={}: {}", connectionId, e.getMessage()));
    }

    public Mono<Map<String, Object>> startOAuth(UUID connectionId, UUID tenantId, UUID userId) {
        return webClient.post()
                .uri("/api/internal/v1/mcp-connections/{connectionId}/start-oauth", connectionId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .doOnError(e -> log.error("McpRuntime startOAuth failed connectionId={}: {}", connectionId, e.getMessage()));
    }

    public Mono<Map<String, Object>> refreshConnection(UUID connectionId, UUID tenantId, UUID userId) {
        return webClient.post()
                .uri("/api/internal/v1/mcp-connections/{connectionId}/refresh", connectionId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .doOnError(e -> log.error("McpRuntime refreshConnection failed connectionId={}: {}", connectionId, e.getMessage()));
    }

    public Mono<Map<String, Object>> getConnectionStatus(UUID connectionId, UUID tenantId, UUID userId) {
        return webClient.get()
                .uri("/api/internal/v1/mcp-connections/{connectionId}/status", connectionId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .doOnError(e -> log.error("McpRuntime getConnectionStatus failed connectionId={}: {}", connectionId, e.getMessage()));
    }

    public Mono<Void> deleteConnection(UUID connectionId, UUID tenantId, UUID userId) {
        return webClient.method(HttpMethod.DELETE)
                .uri("/api/internal/v1/mcp-connections/{connectionId}", connectionId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .bodyToMono(Void.class)
                .doOnError(e -> log.error("McpRuntime deleteConnection failed connectionId={}: {}", connectionId, e.getMessage()));
    }

    /**
     * Forwards the OAuth callback to MCP Runtime and returns the redirect location.
     * Returns the raw response status and Location header so the proxy controller
     * can relay the redirect to the user's browser.
     */
    public Mono<org.springframework.http.ResponseEntity<Void>> oauthCallback(String code, String state) {
        return webClient.get()
                .uri(u -> u.path("/api/internal/v1/mcp-connections/oauth/callback")
                        .queryParam("code", code)
                        .queryParam("state", state)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is3xxRedirection, response ->
                        Mono.error(new OAuthRedirectException(
                                response.headers().asHttpHeaders().getLocation())))
                .bodyToMono(Void.class)
                .thenReturn(org.springframework.http.ResponseEntity.<Void>noContent().build())
                .doOnError(e -> {
                    if (e instanceof OAuthRedirectException ore) {
                        log.debug("McpRuntime OAuth callback redirect to: {}", ore.getLocation());
                    } else {
                        log.error("McpRuntime oauthCallback failed: {}", e.getMessage());
                    }
                });
    }

    /** Carries the redirect location from MCP Runtime's OAuth callback response. */
    public static class OAuthRedirectException extends RuntimeException {
        private final java.net.URI location;

        public OAuthRedirectException(java.net.URI location) {
            super("OAuth redirect to " + location);
            this.location = location;
        }

        public java.net.URI getLocation() {
            return location;
        }
    }
}
