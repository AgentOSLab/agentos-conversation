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
public class AgentRuntimeClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public AgentRuntimeClient(@Qualifier("agentRuntimeWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<Map<String, Object>> submitTask(Map<String, Object> taskRequest, UUID tenantId, UUID userId) {
        log.debug("Agent Runtime call: endpoint=/api/internal/v1/tasks tenant={} userId={}", tenantId, userId);
        return webClient.post()
                .uri("/api/internal/v1/tasks")
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .bodyValue(taskRequest)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnSuccess(resp -> {
                    Object taskId = resp != null ? resp.get("id") : null;
                    log.info("Agent Runtime task submitted: taskId={}", taskId);
                })
                .doOnError(e -> log.error("Agent Runtime call failed: error={}", e.getMessage()));
    }

    public Mono<Map<String, Object>> getTask(UUID taskId, UUID tenantId) {
        log.debug("Agent Runtime call: endpoint=/api/internal/v1/tasks/{}", taskId);
        return webClient.get()
                .uri("/api/internal/v1/tasks/{taskId}", taskId)
                .header("X-Tenant-Id", tenantId.toString())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.error("Agent Runtime call failed: taskId={} error={}", taskId, e.getMessage()));
    }

    public Mono<Map<String, Object>> submitHumanInput(UUID taskId, Map<String, Object> input,
                                                       UUID tenantId, UUID userId) {
        return webClient.post()
                .uri("/api/internal/v1/tasks/{taskId}/human-input", taskId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .bodyValue(input)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.error("Failed to submit human input for task {}: {}", taskId, e.getMessage()));
    }

    public Mono<Void> cancelTask(UUID taskId, UUID tenantId) {
        return webClient.post()
                .uri("/api/internal/v1/tasks/{taskId}/cancel", taskId)
                .header("X-Tenant-Id", tenantId.toString())
                .retrieve()
                .bodyToMono(Void.class)
                .doOnError(e -> log.error("Failed to cancel task {}: {}", taskId, e.getMessage()));
    }

    /**
     * Checks whether the given agent is ready to execute for the given user.
     *
     * Returns a map with:
     *   "ready": Boolean — false means task must NOT be created
     *   "issues": List — structured issue objects each containing type/severity/message/actionUrl
     *
     * On HTTP error (Agent Runtime unavailable), returns {"ready": true, "issues": []}
     * to avoid blocking execution when the readiness service is temporarily unreachable.
     * The actual execution will fail gracefully if a required resource is unavailable.
     */
    public Mono<Map<String, Object>> checkReadiness(UUID agentId, UUID userId, UUID tenantId) {
        return webClient.get()
                .uri("/api/internal/v1/agent-readiness/{agentId}", agentId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .onErrorResume(e -> {
                    log.warn("Readiness check unavailable for agent {}: {}. Proceeding with task creation.",
                            agentId, e.getMessage());
                    // Fail-open: readiness check is a best-effort guard, not a hard gate
                    return Mono.just(Map.of("ready", true, "issues", List.of()));
                });
    }
}
