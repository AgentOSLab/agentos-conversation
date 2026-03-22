package com.agentos.conversation.api.platform;

import com.agentos.common.iam.IamActions;
import com.agentos.common.iam.ResourceArn;
import com.agentos.conversation.security.IamPep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform execution console — cross-tenant tasks, workflow runs, and sandbox views.
 * Proxies to internal platform APIs on runtimes (service token + mesh trust).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/platform/execution")
public class PlatformExecutionController {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final IamPep iamPep;
    private final WebClient agentRuntimeClient;
    private final WebClient workflowRuntimeClient;
    private final WebClient sandboxWebClient;

    public PlatformExecutionController(
            IamPep iamPep,
            @Qualifier("agentRuntimeWebClient") WebClient agentRuntimeClient,
            @Qualifier("workflowRuntimeWebClient") WebClient workflowRuntimeClient,
            @Qualifier("sandboxWebClient") WebClient sandboxWebClient) {
        this.iamPep = iamPep;
        this.agentRuntimeClient = agentRuntimeClient;
        this.workflowRuntimeClient = workflowRuntimeClient;
        this.sandboxWebClient = sandboxWebClient;
    }

    private String executionArn(UUID platformTenantId) {
        return ResourceArn.userSystemPlatformExecutionWildcard(platformTenantId);
    }

    // ── Tasks ───────────────────────────────────────────────────────────────

    @GetMapping("/tasks")
    public Mono<ResponseEntity<Map<String, Object>>> listTasks(
            @RequestHeader("X-Tenant-Id") UUID platformTenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) UUID taskId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) String createdAfter,
            @RequestParam(required = false) String createdBefore,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "0") int page) {
        return iamPep.require(platformTenantId, userId, IamActions.USER_SYSTEM_PLATFORM_EXECUTION_READ,
                        executionArn(platformTenantId))
                .then(agentRuntimeClient.get()
                        .uri(u -> u.path("/api/internal/v1/platform/tasks")
                                .queryParam("pageSize", pageSize)
                                .queryParam("page", page)
                                .queryParamIfPresent("tenantId", Optional.ofNullable(tenantId))
                                .queryParamIfPresent("userId", Optional.ofNullable(actorId))
                                .queryParamIfPresent("taskId", Optional.ofNullable(taskId))
                                .queryParamIfPresent("status", Optional.ofNullable(status))
                                .queryParamIfPresent("priority", Optional.ofNullable(priority))
                                .queryParamIfPresent("agentId", Optional.ofNullable(agentId))
                                .queryParamIfPresent("createdAfter", Optional.ofNullable(createdAfter))
                                .queryParamIfPresent("createdBefore", Optional.ofNullable(createdBefore))
                                .build())
                        .header("X-Tenant-Id", platformTenantId.toString())
                        .header("X-User-Id", userId.toString())
                        .retrieve()
                        .toEntity(MAP_TYPE));
    }

    @GetMapping("/tasks/{taskId}")
    public Mono<ResponseEntity<Map<String, Object>>> getTask(
            @RequestHeader("X-Tenant-Id") UUID platformTenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID taskId,
            @RequestParam UUID tenantId) {
        return iamPep.require(platformTenantId, userId, IamActions.USER_SYSTEM_PLATFORM_EXECUTION_READ,
                        executionArn(platformTenantId))
                .then(agentRuntimeClient.get()
                        .uri(u -> u.path("/api/internal/v1/platform/tasks/{taskId}")
                                .queryParam("tenantId", tenantId)
                                .build(taskId))
                        .header("X-Tenant-Id", platformTenantId.toString())
                        .header("X-User-Id", userId.toString())
                        .retrieve()
                        .toEntity(MAP_TYPE));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Mono<ResponseEntity<Map<String, Object>>> cancelTask(
            @RequestHeader("X-Tenant-Id") UUID platformTenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID taskId,
            @RequestParam UUID tenantId,
            @RequestBody(required = false) Map<String, Object> body) {
        return iamPep.require(platformTenantId, userId, IamActions.USER_SYSTEM_PLATFORM_EXECUTION_INTERVENE,
                        executionArn(platformTenantId))
                .then(agentRuntimeClient.post()
                        .uri(u -> u.path("/api/internal/v1/platform/tasks/{taskId}/cancel")
                                .queryParam("tenantId", tenantId)
                                .build(taskId))
                        .header("X-Tenant-Id", platformTenantId.toString())
                        .header("X-User-Id", userId.toString())
                        .bodyValue(body != null ? body : Map.of())
                        .retrieve()
                        .toEntity(MAP_TYPE));
    }

    @PostMapping("/tasks/{taskId}/pause")
    public Mono<ResponseEntity<Map<String, Object>>> pauseTask(
            @RequestHeader("X-Tenant-Id") UUID platformTenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID taskId,
            @RequestParam UUID tenantId) {
        return iamPep.require(platformTenantId, userId, IamActions.USER_SYSTEM_PLATFORM_EXECUTION_INTERVENE,
                        executionArn(platformTenantId))
                .then(agentRuntimeClient.post()
                        .uri(u -> u.path("/api/internal/v1/platform/tasks/{taskId}/pause")
                                .queryParam("tenantId", tenantId)
                                .build(taskId))
                        .header("X-Tenant-Id", platformTenantId.toString())
                        .header("X-User-Id", userId.toString())
                        .retrieve()
                        .toEntity(MAP_TYPE));
    }

    @PostMapping("/tasks/{taskId}/resume")
    public Mono<ResponseEntity<Map<String, Object>>> resumeTask(
            @RequestHeader("X-Tenant-Id") UUID platformTenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID taskId,
            @RequestParam UUID tenantId) {
        return iamPep.require(platformTenantId, userId, IamActions.USER_SYSTEM_PLATFORM_EXECUTION_INTERVENE,
                        executionArn(platformTenantId))
                .then(agentRuntimeClient.post()
                        .uri(u -> u.path("/api/internal/v1/platform/tasks/{taskId}/resume")
                                .queryParam("tenantId", tenantId)
                                .build(taskId))
                        .header("X-Tenant-Id", platformTenantId.toString())
                        .header("X-User-Id", userId.toString())
                        .retrieve()
                        .toEntity(MAP_TYPE));
    }

    // ── Workflow executions ─────────────────────────────────────────────────

    @GetMapping("/workflow-executions")
    public Mono<ResponseEntity<Map<String, Object>>> listWorkflowExecutions(
            @RequestHeader("X-Tenant-Id") UUID platformTenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String workflowId,
            @RequestParam(required = false) UUID executionId,
            @RequestParam(required = false) String createdAfter,
            @RequestParam(required = false) String createdBefore,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return iamPep.require(platformTenantId, userId, IamActions.USER_SYSTEM_PLATFORM_EXECUTION_READ,
                        executionArn(platformTenantId))
                .then(workflowRuntimeClient.get()
                        .uri(u -> u.path("/api/internal/v1/platform/workflow-executions")
                                .queryParam("page", page)
                                .queryParam("pageSize", pageSize)
                                .queryParamIfPresent("tenantId", Optional.ofNullable(tenantId))
                                .queryParamIfPresent("status", Optional.ofNullable(status))
                                .queryParamIfPresent("workflowId", Optional.ofNullable(workflowId))
                                .queryParamIfPresent("executionId", Optional.ofNullable(executionId))
                                .queryParamIfPresent("createdAfter", Optional.ofNullable(createdAfter))
                                .queryParamIfPresent("createdBefore", Optional.ofNullable(createdBefore))
                                .build())
                        .header("X-Tenant-Id", platformTenantId.toString())
                        .header("X-User-Id", userId.toString())
                        .retrieve()
                        .toEntity(MAP_TYPE));
    }

    @GetMapping("/workflow-executions/{executionId}")
    public Mono<ResponseEntity<Map<String, Object>>> getWorkflowExecution(
            @RequestHeader("X-Tenant-Id") UUID platformTenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID executionId,
            @RequestParam String tenantId) {
        return iamPep.require(platformTenantId, userId, IamActions.USER_SYSTEM_PLATFORM_EXECUTION_READ,
                        executionArn(platformTenantId))
                .then(workflowRuntimeClient.get()
                        .uri(u -> u.path("/api/internal/v1/platform/workflow-executions/{id}")
                                .queryParam("tenantId", tenantId)
                                .build(executionId))
                        .header("X-Tenant-Id", platformTenantId.toString())
                        .header("X-User-Id", userId.toString())
                        .retrieve()
                        .toEntity(MAP_TYPE));
    }

    @GetMapping("/workflow-executions/{executionId}/steps")
    public Mono<ResponseEntity<List<Map<String, Object>>>> workflowSteps(
            @RequestHeader("X-Tenant-Id") UUID platformTenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID executionId,
            @RequestParam String tenantId) {
        return iamPep.require(platformTenantId, userId, IamActions.USER_SYSTEM_PLATFORM_EXECUTION_READ,
                        executionArn(platformTenantId))
                .then(workflowRuntimeClient.get()
                        .uri(u -> u.path("/api/internal/v1/platform/workflow-executions/{id}/steps")
                                .queryParam("tenantId", tenantId)
                                .build(executionId))
                        .header("X-Tenant-Id", platformTenantId.toString())
                        .header("X-User-Id", userId.toString())
                        .retrieve()
                        .toEntity(LIST_MAP_TYPE));
    }

    @PostMapping("/workflow-executions/{executionId}/cancel")
    public Mono<ResponseEntity<Map<String, Object>>> cancelWorkflowExecution(
            @RequestHeader("X-Tenant-Id") UUID platformTenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID executionId,
            @RequestParam String tenantId,
            @RequestBody(required = false) Map<String, String> body) {
        return iamPep.require(platformTenantId, userId, IamActions.USER_SYSTEM_PLATFORM_EXECUTION_INTERVENE,
                        executionArn(platformTenantId))
                .then(workflowRuntimeClient.post()
                        .uri(u -> u.path("/api/internal/v1/platform/workflow-executions/{id}/cancel")
                                .queryParam("tenantId", tenantId)
                                .build(executionId))
                        .header("X-Tenant-Id", platformTenantId.toString())
                        .header("X-User-Id", userId.toString())
                        .bodyValue(body != null ? body : Map.of())
                        .retrieve()
                        .toEntity(MAP_TYPE));
    }

    // ── Sandbox ─────────────────────────────────────────────────────────────

    @GetMapping("/sandbox/processes")
    public Mono<ResponseEntity<Map<String, Object>>> listSandboxProcesses(
            @RequestHeader("X-Tenant-Id") UUID platformTenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startedAfter,
            @RequestParam(required = false) String startedBefore,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return iamPep.require(platformTenantId, userId, IamActions.USER_SYSTEM_PLATFORM_EXECUTION_READ,
                        executionArn(platformTenantId))
                .then(sandboxWebClient.get()
                        .uri(u -> u.path("/api/internal/v1/platform/sandbox/processes")
                                .queryParam("page", page)
                                .queryParam("pageSize", pageSize)
                                .queryParamIfPresent("tenantId", Optional.ofNullable(tenantId))
                                .queryParamIfPresent("status", Optional.ofNullable(status))
                                .queryParamIfPresent("startedAfter", Optional.ofNullable(startedAfter))
                                .queryParamIfPresent("startedBefore", Optional.ofNullable(startedBefore))
                                .build())
                        .header("X-Tenant-Id", platformTenantId.toString())
                        .header("X-User-Id", userId.toString())
                        .retrieve()
                        .toEntity(MAP_TYPE));
    }

    @GetMapping("/sandbox/executions")
    public Mono<ResponseEntity<Map<String, Object>>> listSandboxExecutions(
            @RequestHeader("X-Tenant-Id") UUID platformTenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String createdAfter,
            @RequestParam(required = false) String createdBefore,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return iamPep.require(platformTenantId, userId, IamActions.USER_SYSTEM_PLATFORM_EXECUTION_READ,
                        executionArn(platformTenantId))
                .then(sandboxWebClient.get()
                        .uri(u -> u.path("/api/internal/v1/platform/sandbox/executions")
                                .queryParam("page", page)
                                .queryParam("pageSize", pageSize)
                                .queryParamIfPresent("tenantId", Optional.ofNullable(tenantId))
                                .queryParamIfPresent("status", Optional.ofNullable(status))
                                .queryParamIfPresent("createdAfter", Optional.ofNullable(createdAfter))
                                .queryParamIfPresent("createdBefore", Optional.ofNullable(createdBefore))
                                .build())
                        .header("X-Tenant-Id", platformTenantId.toString())
                        .header("X-User-Id", userId.toString())
                        .retrieve()
                        .toEntity(MAP_TYPE));
    }

    @PostMapping("/sandbox/processes/{processId}/stop")
    public Mono<ResponseEntity<Void>> stopSandboxProcess(
            @RequestHeader("X-Tenant-Id") UUID platformTenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID processId,
            @RequestParam String tenantId,
            @RequestBody(required = false) Map<String, String> body) {
        return iamPep.require(platformTenantId, userId, IamActions.USER_SYSTEM_PLATFORM_EXECUTION_INTERVENE,
                        executionArn(platformTenantId))
                .then(sandboxWebClient.post()
                        .uri(u -> u.path("/api/internal/v1/platform/sandbox/processes/{id}/stop")
                                .queryParam("tenantId", tenantId)
                                .build(processId))
                        .header("X-Tenant-Id", platformTenantId.toString())
                        .header("X-User-Id", userId.toString())
                        .bodyValue(body != null ? body : Map.of())
                        .retrieve()
                        .toBodilessEntity());
    }
}
