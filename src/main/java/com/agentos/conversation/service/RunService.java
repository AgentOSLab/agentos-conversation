package com.agentos.conversation.service;

import com.agentos.common.audit.AuditAction;
import com.agentos.common.audit.AuditClient;
import com.agentos.common.audit.AuditResourceType;
import com.agentos.conversation.model.dto.RunResponse;
import com.agentos.conversation.model.entity.RunEntity;
import com.agentos.conversation.repository.RunRepository;
import com.agentos.common.model.PageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RunService {

    @Autowired(required = false)
    private AuditClient auditClient;

    private final RunRepository runRepository;
    private final ObjectMapper objectMapper;

    public Mono<RunEntity> createRun(UUID sessionId, UUID tenantId, UUID userId,
                                     String routeType, UUID inputMessageId) {
        RunEntity run = RunEntity.builder()
                .sessionId(sessionId)
                .tenantId(tenantId)
                .userId(userId)
                .routeType(routeType)
                .inputMessageId(inputMessageId)
                .build();

        return runRepository.save(run)
                .doOnSuccess(r -> log.info("Run created: runId={} sessionId={} tenant={}", r.getId(), sessionId, tenantId))
                .flatMap(r -> audit(tenantId, userId, AuditAction.RUN_CREATE,
                        AuditResourceType.RUN, r.getId().toString(),
                        Map.of("sessionId", sessionId.toString(),
                                "routeType", String.valueOf(routeType)))
                        .thenReturn(r));
    }

    public Mono<RunEntity> getRun(UUID runId, UUID tenantId) {
        return runRepository.findByIdAndTenantId(runId, tenantId);
    }

    public Mono<PageResponse<RunResponse>> listRuns(UUID sessionId, UUID tenantId, int limit, long offset) {
        return runRepository.countBySession(sessionId, tenantId)
                .flatMap(total -> runRepository.findBySession(sessionId, tenantId, limit, (int) offset)
                        .map(RunResponse::fromEntity)
                        .collectList()
                        .map(items -> PageResponse.of(items, null, total)));
    }

    public Mono<Void> markRunning(UUID runId) {
        log.info("Run executing: runId={}", runId);
        return runRepository.markRunning(runId, OffsetDateTime.now());
    }

    public Mono<Void> setTaskId(UUID runId, UUID taskId) {
        return runRepository.setTaskId(runId, taskId);
    }

    public Mono<Void> updateStatus(UUID runId, String status) {
        return runRepository.updateStatus(runId, status);
    }

    public Mono<Void> completeRun(UUID runId, UUID outputMessageId,
                                   Map<String, Object> tokenUsage, String lastEventId) {
        return runRepository.completeRun(runId, "completed", outputMessageId,
                toJson(tokenUsage), lastEventId, OffsetDateTime.now())
                .doOnSuccess(v -> log.info("Run completed: runId={}", runId));
    }

    public Mono<Void> failRun(UUID runId, String code, String message, boolean retryable) {
        Map<String, Object> error = Map.of(
                "code", code,
                "message", message,
                "retryable", retryable
        );
        return runRepository.failRun(runId, toJson(error), OffsetDateTime.now())
                .doOnSuccess(v -> log.info("Run failed: runId={} error={}", runId, code));
    }

    public Mono<Void> cancelRun(UUID runId) {
        return runRepository.completeRun(runId, "cancelled", null, null, null, OffsetDateTime.now())
                .doOnSuccess(v -> log.info("Run cancelled: runId={}", runId))
                .then(audit(null, null, AuditAction.RUN_CANCEL,
                        AuditResourceType.RUN, runId.toString(), null));
    }

    public Mono<Void> updateLastEventId(UUID runId, String lastEventId) {
        return runRepository.updateLastEventId(runId, lastEventId);
    }

    private Mono<Void> audit(UUID tenantId, UUID actorId, String action,
                              String resourceType, String resourceId,
                              Map<String, Object> details) {
        if (auditClient == null) return Mono.empty();
        return auditClient.record(tenantId, actorId, action, resourceType, resourceId, "success", details);
    }

    private String toJson(Map<String, Object> map) {
        if (map == null) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize to JSON: {}", e.getMessage());
            return "{}";
        }
    }
}
