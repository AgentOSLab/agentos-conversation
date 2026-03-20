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
public class HubClient {

    private final WebClient webClient;

    public HubClient(@Qualifier("hubWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<Map<String, Object>> getAgent(UUID agentId, UUID tenantId) {
        log.debug("Hub call: endpoint=/api/v1/agents/{} tenant={}", agentId, tenantId);
        return webClient.get()
                .uri("/api/v1/agents/{agentId}", agentId)
                .header("X-Tenant-Id", tenantId.toString())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.error("Hub call failed: endpoint=/api/v1/agents/{} error={}", agentId, e.getMessage()));
    }

    public Mono<Map<String, Object>> getSkillPackage(UUID skillPackageId, UUID tenantId) {
        log.debug("Hub call: endpoint=/api/v1/mcp-skill-packages/{} tenant={}", skillPackageId, tenantId);
        return webClient.get()
                .uri("/api/v1/mcp-skill-packages/{id}", skillPackageId)
                .header("X-Tenant-Id", tenantId.toString())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.error("Hub call failed: endpoint=/api/v1/mcp-skill-packages/{} error={}", skillPackageId, e.getMessage()));
    }
}
