package com.agentos.conversation.api;

import com.agentos.conversation.model.dto.*;
import com.agentos.conversation.orchestration.MessageOrchestrator;
import com.agentos.conversation.service.ConversationSessionService;
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

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class ConversationSessionController {

    private final ConversationSessionService sessionService;
    private final MessageOrchestrator messageOrchestrator;

    @PostMapping
    public Mono<ResponseEntity<ConversationSessionResponse>> createSession(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody CreateSessionRequest request) {

        log.info("Creating session: tenant={} userId={} type={}", tenantId, userId, request.getSessionType());
        return sessionService.createSession(tenantId, userId, request)
                .map(ConversationSessionResponse::fromEntity)
                .map(resp -> ResponseEntity.status(HttpStatus.CREATED).body(resp));
    }

    @GetMapping
    public Mono<ResponseEntity<PageResponse<ConversationSessionResponse>>> listSessions(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sessionType,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") long offset) {

        log.debug("Listing sessions: tenant={} userId={} status={} type={}", tenantId, userId, status, sessionType);
        return sessionService.listSessions(tenantId, userId, status, sessionType, limit, offset)
                .map(page -> page.map(ConversationSessionResponse::fromEntity))
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{sessionId}")
    public Mono<ResponseEntity<ConversationSessionResponse>> getSession(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID sessionId) {

        log.debug("Getting session: sessionId={} tenant={}", sessionId, tenantId);
        return sessionService.getSession(tenantId, sessionId)
                .map(ConversationSessionResponse::fromEntity)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{sessionId}/title")
    public Mono<ResponseEntity<ConversationSessionResponse>> updateTitle(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID sessionId,
            @RequestBody Map<String, String> body) {

        return sessionService.updateTitle(tenantId, sessionId, body.get("title"))
                .map(ConversationSessionResponse::fromEntity)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{sessionId}/mcp-tools")
    public Mono<ResponseEntity<ConversationSessionResponse>> updateMcpToolConfig(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID sessionId,
            @RequestBody Map<String, Object> config) {

        return sessionService.updateMcpToolConfig(tenantId, sessionId, config)
                .map(ConversationSessionResponse::fromEntity)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping("/{sessionId}/complete")
    public Mono<ResponseEntity<ConversationSessionResponse>> completeSession(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID sessionId) {

        log.info("Completing session: sessionId={} tenant={}", sessionId, tenantId);
        return sessionService.updateSessionStatus(tenantId, sessionId, "completed")
                .map(ConversationSessionResponse::fromEntity)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping("/{sessionId}/archive")
    public Mono<ResponseEntity<ConversationSessionResponse>> archiveSession(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID sessionId) {

        log.info("Archiving session: sessionId={} tenant={}", sessionId, tenantId);
        return sessionService.updateSessionStatus(tenantId, sessionId, "archived")
                .map(ConversationSessionResponse::fromEntity)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping("/{sessionId}/messages")
    public Mono<ResponseEntity<RunResponse>> sendMessage(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody SendMessageRequest request) {

        CreateRunRequest runRequest = CreateRunRequest.builder()
                .content(request.getContent())
                .attachments(request.getAttachments())
                .build();

        return messageOrchestrator.createAndExecuteRun(tenantId, userId, sessionId, runRequest)
                .map(run -> ResponseEntity.status(HttpStatus.ACCEPTED).body(run));
    }

    @GetMapping("/{sessionId}/messages")
    public Mono<ResponseEntity<PageResponse<ConversationMessageResponse>>> getMessages(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") long offset) {

        return sessionService.getMessagesPaged(sessionId, limit, offset)
                .map(page -> page.map(ConversationMessageResponse::fromEntity))
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{sessionId}/messages/{messageId}/pin")
    public Mono<ResponseEntity<Void>> pinMessage(
            @PathVariable UUID sessionId,
            @PathVariable UUID messageId,
            @RequestParam(defaultValue = "true") boolean pinned) {

        return sessionService.pinMessage(messageId, pinned)
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    /**
     * Session-level SSE stream. Delivers a multiplexed view of every run event within this session.
     * Clients subscribe once and receive events from all current and future runs in the session.
     */
    @GetMapping(value = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamSessionEvents(
            @PathVariable UUID sessionId) {

        return messageOrchestrator.streamSessionEvents(sessionId);
    }
}
