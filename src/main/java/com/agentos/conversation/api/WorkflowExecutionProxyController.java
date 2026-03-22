package com.agentos.conversation.api;

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
import java.util.UUID;

/**
 * Proxies read-only workflow execution views to Workflow Runtime internal APIs
 * ({@code /api/internal/v1/workflow-executions/**}).
 *
 * <p>ADR-041: browsers must not call workflow-runtime directly; use {@code scope=tenant} for
 * tenant-wide observability after PDP checks here.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/workflow-executions")
public class WorkflowExecutionProxyController {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final IamPep iamPep;
    private final WebClient workflowRuntimeWebClient;

    public WorkflowExecutionProxyController(
            IamPep iamPep,
            @Qualifier("workflowRuntimeWebClient") WebClient workflowRuntimeWebClient) {
        this.iamPep = iamPep;
        this.workflowRuntimeWebClient = workflowRuntimeWebClient;
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_TASK_READ,
                        ResourceArn.agentRuntimeTaskWildcard(tenantId))
                .then(workflowRuntimeWebClient.get()
                        .uri(u -> u.path("/api/internal/v1/workflow-executions")
                                .queryParam("page", page)
                                .queryParam("pageSize", pageSize)
                                .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                                .build())
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("X-User-Id", userId.toString())
                        .retrieve()
                        .toEntity(MAP_TYPE));
    }

    @GetMapping("/{executionId}")
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID executionId) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_TASK_READ,
                        ResourceArn.agentRuntimeTaskWildcard(tenantId))
                .then(workflowRuntimeWebClient.get()
                        .uri(u -> u.path("/api/internal/v1/workflow-executions/{id}")
                                .queryParam("scope", "tenant")
                                .build(executionId))
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("X-User-Id", userId.toString())
                        .retrieve()
                        .toEntity(MAP_TYPE));
    }

    @GetMapping("/{executionId}/steps")
    public Mono<ResponseEntity<List<Map<String, Object>>>> steps(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID executionId) {
        return iamPep.require(tenantId, userId, IamActions.AGENT_RUNTIME_TASK_READ,
                        ResourceArn.agentRuntimeTaskWildcard(tenantId))
                .then(workflowRuntimeWebClient.get()
                        .uri(u -> u.path("/api/internal/v1/workflow-executions/{id}/steps")
                                .queryParam("scope", "tenant")
                                .build(executionId))
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("X-User-Id", userId.toString())
                        .retrieve()
                        .toEntity(LIST_MAP_TYPE));
    }
}
