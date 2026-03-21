package com.agentos.conversation.api;

import com.agentos.conversation.integration.McpRuntimeClient;
import com.agentos.conversation.integration.McpRuntimeClient.OAuthRedirectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * Proxies MCP connection management requests to MCP Runtime's internal endpoints
 * ({@code /api/internal/v1/mcp-connections/**}).
 *
 * <p>MCP Runtime's user-facing connection API is internal-only; all external client
 * access flows through Conversation (ADR-041). IAM/PEP authorization is enforced by
 * MCP Runtime on the forwarded headers — this proxy does not duplicate it.
 *
 * <p>The OAuth callback ({@code GET /api/v1/mcp-connections/oauth/callback}) is proxied
 * here so that the external redirect URI registered with OAuth providers remains stable
 * regardless of internal routing changes.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/mcp-connections")
@RequiredArgsConstructor
public class McpConnectionProxyController {

    private final McpRuntimeClient mcpRuntimeClient;

    @GetMapping("/me")
    public Mono<ResponseEntity<Map<String, Object>>> listMyConnections(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID activationId) {
        return mcpRuntimeClient.listMyConnections(tenantId, userId, pageSize, pageToken, status, activationId)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{activationId}/begin")
    public Mono<ResponseEntity<Map<String, Object>>> beginConnection(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID activationId,
            @RequestBody(required = false) Map<String, Object> request) {
        return mcpRuntimeClient.beginConnection(activationId, tenantId, userId, request)
                .map(body -> ResponseEntity.status(HttpStatus.CREATED).body(body));
    }

    @PatchMapping("/{connectionId}/user-config")
    public Mono<ResponseEntity<Map<String, Object>>> updateUserConfig(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID connectionId,
            @RequestBody Map<String, Object> request) {
        return mcpRuntimeClient.updateUserConfig(connectionId, tenantId, userId, request)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{connectionId}/set-credential")
    public Mono<ResponseEntity<Map<String, Object>>> setUserCredential(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID connectionId,
            @RequestBody Map<String, Object> request) {
        return mcpRuntimeClient.setUserCredential(connectionId, tenantId, userId, request)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{connectionId}/start-oauth")
    public Mono<ResponseEntity<Map<String, Object>>> startOAuth(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID connectionId) {
        return mcpRuntimeClient.startOAuth(connectionId, tenantId, userId)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{connectionId}/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refreshConnection(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID connectionId) {
        return mcpRuntimeClient.refreshConnection(connectionId, tenantId, userId)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{connectionId}/status")
    public Mono<ResponseEntity<Map<String, Object>>> getConnectionStatus(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID connectionId) {
        return mcpRuntimeClient.getConnectionStatus(connectionId, tenantId, userId)
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{connectionId}")
    public Mono<ResponseEntity<Void>> deleteConnection(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID connectionId) {
        return mcpRuntimeClient.deleteConnection(connectionId, tenantId, userId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    /**
     * OAuth 2.0 callback — receives the authorization code from the identity provider
     * and forwards it to MCP Runtime for token exchange, then relays the redirect
     * to the user's browser.
     *
     * <p>This endpoint must remain externally reachable because OAuth providers are
     * configured with this redirect URI. Routing it through Conversation keeps the
     * URL stable while enforcing the layer boundary.
     */
    @GetMapping("/oauth/callback")
    public Mono<ResponseEntity<Void>> oauthCallback(
            @RequestParam String code,
            @RequestParam String state) {
        return mcpRuntimeClient.oauthCallback(code, state)
                .onErrorResume(OAuthRedirectException.class, e -> {
                    URI location = e.getLocation();
                    if (location != null) {
                        return Mono.just(ResponseEntity.status(HttpStatus.FOUND)
                                .location(location)
                                .<Void>build());
                    }
                    return Mono.just(ResponseEntity.status(HttpStatus.FOUND)
                            .location(URI.create("/mcp-error?reason=missing_redirect"))
                            .<Void>build());
                })
                .onErrorResume(e -> {
                    log.error("OAuth callback proxy error: {}", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.FOUND)
                            .location(URI.create("/mcp-error?reason=" + encode(e.getMessage())))
                            .<Void>build());
                });
    }

    private static String encode(String value) {
        if (value == null) return "unknown";
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "error";
        }
    }
}
