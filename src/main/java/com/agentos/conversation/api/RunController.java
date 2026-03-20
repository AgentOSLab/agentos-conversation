package com.agentos.conversation.api;

import com.agentos.conversation.model.dto.*;
import com.agentos.conversation.orchestration.MessageOrchestrator;
import com.agentos.conversation.service.RunService;
import com.agentos.common.model.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Unified Run-based API. Replaces the old MessageController's two-step flow
 * (POST message → GET task events) with a single abstraction:
 *   POST /runs → get runId → GET /runs/{runId}/events (SSE)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}")
@RequiredArgsConstructor
public class RunController {

    private final MessageOrchestrator orchestrator;
    private final RunService runService;

    @PostMapping("/runs")
    public Mono<ResponseEntity<RunResponse>> createRun(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody CreateRunRequest request) {

        log.info("Creating run: sessionId={} tenant={} userId={}", sessionId, tenantId, userId);
        return orchestrator.createAndExecuteRun(tenantId, userId, sessionId, request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamRunEvents(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @PathVariable UUID runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {

        return orchestrator.streamRunEvents(runId, sessionId, tenantId, userId, lastEventId);
    }

    @PostMapping("/runs/{runId}/cancel")
    public Mono<ResponseEntity<RunResponse>> cancelRun(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID sessionId,
            @PathVariable UUID runId) {

        log.info("Cancelling run: runId={} sessionId={} tenant={}", runId, sessionId, tenantId);
        return orchestrator.cancelRun(runId, tenantId)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/runs/{runId}/input")
    public Mono<ResponseEntity<Void>> submitInput(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @PathVariable UUID runId,
            @Valid @RequestBody SubmitRunInputRequest request) {

        return orchestrator.submitHumanInput(runId, tenantId, userId, request)
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    @PostMapping("/runs/{runId}/retry")
    public Mono<ResponseEntity<RunResponse>> retryRun(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @PathVariable UUID runId) {

        return orchestrator.retryRun(runId, sessionId, tenantId, userId)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @GetMapping("/runs/{runId}")
    public Mono<ResponseEntity<RunResponse>> getRunStatus(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID sessionId,
            @PathVariable UUID runId) {

        log.debug("Getting run status: runId={} sessionId={}", runId, sessionId);
        return runService.getRun(runId, tenantId)
                .map(RunResponse::fromEntity)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/runs")
    public Mono<ResponseEntity<PageResponse<RunResponse>>> listRuns(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") long offset) {

        log.debug("Listing runs: sessionId={} tenant={}", sessionId, tenantId);
        return runService.listRuns(sessionId, tenantId, limit, offset)
                .map(ResponseEntity::ok);
    }
}
