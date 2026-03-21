package com.agentos.conversation.api;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Proxies agent readiness checks to Agent Runtime's internal endpoint
 * ({@code /api/internal/v1/agent-readiness/**}).
 *
 * <p>See {@link TaskProxyController} for the layer-boundary rationale.
 */
@RestController
@RequestMapping("/api/v1/agent-readiness")
@RequiredArgsConstructor
public class AgentReadinessProxyController {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final @Qualifier("agentRuntimeWebClient") WebClient agentRuntimeClient;

    @GetMapping("/{agentId}")
    public Mono<ResponseEntity<Map<String, Object>>> checkReadiness(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID agentId) {
        return agentRuntimeClient.get()
                .uri("/api/internal/v1/agent-readiness/{agentId}", agentId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .toEntity(MAP_TYPE);
    }
}
