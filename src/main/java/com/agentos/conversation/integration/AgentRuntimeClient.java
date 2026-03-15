package com.agentos.conversation.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class AgentRuntimeClient {

    private final WebClient webClient;

    public AgentRuntimeClient(@Qualifier("agentRuntimeWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<Map<String, Object>> submitTask(Map<String, Object> taskRequest, UUID tenantId, UUID userId) {
        return webClient.post()
                .uri("/api/v1/tasks")
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .bodyValue(taskRequest)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.error("Agent Runtime task submission failed: {}", e.getMessage()));
    }

    public Flux<String> streamTaskEvents(UUID taskId, UUID tenantId, UUID userId) {
        return webClient.get()
                .uri("/api/v1/tasks/{taskId}/events", taskId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnError(e -> log.error("Agent Runtime SSE stream failed for task {}: {}", taskId, e.getMessage()));
    }

    public Mono<Map<String, Object>> getTask(UUID taskId, UUID tenantId) {
        return webClient.get()
                .uri("/api/v1/tasks/{taskId}", taskId)
                .header("X-Tenant-Id", tenantId.toString())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.error("Failed to get task {}: {}", taskId, e.getMessage()));
    }

    public Mono<Map<String, Object>> submitHumanInput(UUID taskId, Map<String, Object> input,
                                                       UUID tenantId, UUID userId) {
        return webClient.post()
                .uri("/api/v1/tasks/{taskId}/human-input", taskId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .bodyValue(input)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.error("Failed to submit human input for task {}: {}", taskId, e.getMessage()));
    }
}
