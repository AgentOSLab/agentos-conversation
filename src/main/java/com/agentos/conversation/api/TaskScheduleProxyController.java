package com.agentos.conversation.api;

import com.agentos.common.iam.IamActions;
import com.agentos.common.iam.ResourceArn;
import com.agentos.conversation.security.IamPep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Proxies task schedule management to Agent Runtime's internal schedule endpoints
 * ({@code /api/internal/v1/task-schedules/**}).
 *
 * <p>See {@link TaskProxyController} for the layer-boundary rationale and PEP notes.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/task-schedules")
@RequiredArgsConstructor
public class TaskScheduleProxyController {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final IamPep iamPep;
    private final @Qualifier("agentRuntimeWebClient") WebClient agentRuntimeClient;

    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> createSchedule(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestBody Map<String, Object> request) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTaskScheduleWildcard(tenantId))
                .then(agentRuntimeClient.post()
                .uri("/api/internal/v1/task-schedules")
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .bodyValue(request)
                .retrieve()
                .toEntity(MAP_TYPE));
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> listSchedules(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "0") int page) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTaskScheduleWildcard(tenantId))
                .then(agentRuntimeClient.get()
                .uri(u -> u.path("/api/internal/v1/task-schedules")
                        .queryParam("pageSize", pageSize)
                        .queryParam("page", page)
                        .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                        .queryParamIfPresent("agentId", java.util.Optional.ofNullable(agentId))
                        .build())
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .toEntity(MAP_TYPE));
    }

    @GetMapping("/{scheduleId}")
    public Mono<ResponseEntity<Map<String, Object>>> getSchedule(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID scheduleId) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTaskSchedule(tenantId, scheduleId))
                .then(agentRuntimeClient.get()
                .uri("/api/internal/v1/task-schedules/{scheduleId}", scheduleId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .toEntity(MAP_TYPE));
    }

    @PutMapping("/{scheduleId}")
    public Mono<ResponseEntity<Map<String, Object>>> updateSchedule(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID scheduleId,
            @RequestBody Map<String, Object> request) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTaskSchedule(tenantId, scheduleId))
                .then(agentRuntimeClient.put()
                .uri("/api/internal/v1/task-schedules/{scheduleId}", scheduleId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .bodyValue(request)
                .retrieve()
                .toEntity(MAP_TYPE));
    }

    @DeleteMapping("/{scheduleId}")
    public Mono<ResponseEntity<Void>> deleteSchedule(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID scheduleId) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTaskSchedule(tenantId, scheduleId))
                .then(agentRuntimeClient.delete()
                .uri("/api/internal/v1/task-schedules/{scheduleId}", scheduleId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .toBodilessEntity()
                .map(e -> ResponseEntity.status(e.getStatusCode()).<Void>build()));
    }

    @PostMapping("/{scheduleId}/pause")
    public Mono<ResponseEntity<Map<String, Object>>> pauseSchedule(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID scheduleId) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTaskSchedule(tenantId, scheduleId))
                .then(agentRuntimeClient.post()
                .uri("/api/internal/v1/task-schedules/{scheduleId}/pause", scheduleId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .toEntity(MAP_TYPE));
    }

    @PostMapping("/{scheduleId}/resume")
    public Mono<ResponseEntity<Map<String, Object>>> resumeSchedule(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID scheduleId) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_AGENT_RUN,
                        ResourceArn.agentRuntimeTaskSchedule(tenantId, scheduleId))
                .then(agentRuntimeClient.post()
                .uri("/api/internal/v1/task-schedules/{scheduleId}/resume", scheduleId)
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .toEntity(MAP_TYPE));
    }
}
