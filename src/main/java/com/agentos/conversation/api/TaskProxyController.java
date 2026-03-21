package com.agentos.conversation.api;

import com.agentos.common.iam.IamActions;
import com.agentos.common.iam.ResourceArn;
import com.agentos.conversation.security.IamPep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.beans.factory.annotation.Qualifier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Proxies task management requests from external clients to Agent Runtime's
 * internal task endpoints ({@code /api/internal/v1/tasks/**}).
 *
 * <p>Agent Runtime is an internal execution service; all client access must flow
 * through Conversation (ADR-041). This controller preserves the public API surface
 * at {@code /api/v1/tasks/**} while enforcing the layer boundary.
 *
 * <p>PDP-backed PEP runs here before forwarding. Agent Runtime internal APIs do not
 * re-check IAM; see {@code audit/iam-pep-review-agentos-conversation.md} for action choice
 * vs preset role policies.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskProxyController {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final IamPep iamPep;
    private final @Qualifier("agentRuntimeWebClient") WebClient agentRuntimeClient;

    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> submitTask(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestBody Map<String, Object> request) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        submitTaskResourceArn(tenantId, request))
                .then(agentRuntimeClient.post()
                .uri("/api/internal/v1/tasks")
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .bodyValue(request)
                .retrieve()
                .toEntity(MAP_TYPE));
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> listTasks(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(defaultValue = "false") boolean tenantScope,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "0") int page) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTaskWildcard(tenantId))
                .then(agentRuntimeClient.get()
                .uri(u -> u.path("/api/internal/v1/tasks")
                        .queryParam("pageSize", pageSize)
                        .queryParam("page", page)
                        .queryParam("tenantScope", tenantScope)
                        .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                        .queryParamIfPresent("priority", java.util.Optional.ofNullable(priority))
                        .queryParamIfPresent("agentId", java.util.Optional.ofNullable(agentId))
                        .build())
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .toEntity(MAP_TYPE));
    }

    @GetMapping("/{taskId}")
    public Mono<ResponseEntity<Map<String, Object>>> getTask(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID taskId) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTask(tenantId, taskId))
                .then(agentRuntimeClient.get()
                .uri("/api/internal/v1/tasks/{taskId}", taskId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .toEntity(MAP_TYPE));
    }

    @PostMapping("/{taskId}/cancel")
    public Mono<ResponseEntity<Map<String, Object>>> cancelTask(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID taskId,
            @RequestBody(required = false) Map<String, Object> request) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTask(tenantId, taskId))
                .then(agentRuntimeClient.post()
                .uri("/api/internal/v1/tasks/{taskId}/cancel", taskId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .bodyValue(request != null ? request : Map.of())
                .retrieve()
                .toEntity(MAP_TYPE));
    }

    @PostMapping("/{taskId}/pause")
    public Mono<ResponseEntity<Map<String, Object>>> pauseTask(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID taskId) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTask(tenantId, taskId))
                .then(agentRuntimeClient.post()
                .uri("/api/internal/v1/tasks/{taskId}/pause", taskId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .toEntity(MAP_TYPE));
    }

    @PostMapping("/{taskId}/resume")
    public Mono<ResponseEntity<Map<String, Object>>> resumeTask(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID taskId) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTask(tenantId, taskId))
                .then(agentRuntimeClient.post()
                .uri("/api/internal/v1/tasks/{taskId}/resume", taskId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .toEntity(MAP_TYPE));
    }

    @PostMapping("/{taskId}/takeover")
    public Mono<ResponseEntity<Map<String, Object>>> takeoverTask(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID taskId,
            @RequestBody Map<String, Object> request) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTask(tenantId, taskId))
                .then(agentRuntimeClient.post()
                .uri("/api/internal/v1/tasks/{taskId}/takeover", taskId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .bodyValue(request)
                .retrieve()
                .toEntity(MAP_TYPE));
    }

    @PostMapping("/{taskId}/human-input")
    public Mono<ResponseEntity<Void>> submitHumanInput(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID taskId,
            @RequestBody Map<String, Object> response) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTask(tenantId, taskId))
                .then(agentRuntimeClient.post()
                .uri("/api/internal/v1/tasks/{taskId}/human-input", taskId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .bodyValue(response)
                .retrieve()
                .toBodilessEntity()
                .map(e -> ResponseEntity.status(e.getStatusCode()).<Void>build()));
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> streamTaskEvents(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID taskId) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTask(tenantId, taskId))
                .thenMany(agentRuntimeClient.get()
                .uri("/api/internal/v1/tasks/{taskId}/events", taskId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<Map<String, Object>>>() {}));
    }

    @GetMapping("/{taskId}/timeline")
    public Mono<ResponseEntity<Map<String, Object>>> getTaskTimeline(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID taskId) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTask(tenantId, taskId))
                .then(agentRuntimeClient.get()
                .uri("/api/internal/v1/tasks/{taskId}/timeline", taskId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .toEntity(MAP_TYPE));
    }

    @GetMapping("/{taskId}/environment")
    public Mono<ResponseEntity<Map<String, Object>>> getTaskEnvironment(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID taskId) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTask(tenantId, taskId))
                .then(agentRuntimeClient.get()
                .uri("/api/internal/v1/tasks/{taskId}/environment", taskId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .toEntity(MAP_TYPE));
    }

    private static String submitTaskResourceArn(UUID tenantId, Map<String, Object> request) {
        UUID agentId = parseAgentId(request);
        return agentId != null
                ? ResourceArn.agentRuntimeAgent(tenantId, agentId)
                : ResourceArn.agentRuntimeAgentWildcard(tenantId);
    }

    private static UUID parseAgentId(Map<String, Object> request) {
        Object raw = request != null ? request.get("agentId") : null;
        if (raw == null) {
            return null;
        }
        if (raw instanceof UUID u) {
            return u;
        }
        if (raw instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
