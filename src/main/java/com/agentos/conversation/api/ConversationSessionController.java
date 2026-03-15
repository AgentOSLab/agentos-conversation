package com.agentos.conversation.api;

import com.agentos.conversation.model.dto.*;
import com.agentos.conversation.service.ConversationSessionService;
import com.agentos.common.model.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class ConversationSessionController {

    private final ConversationSessionService sessionService;

    @PostMapping
    public Mono<ResponseEntity<ConversationSessionResponse>> createSession(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody CreateSessionRequest request) {

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

        return sessionService.listSessions(tenantId, userId, status, sessionType, limit, offset)
                .map(page -> page.map(ConversationSessionResponse::fromEntity))
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{sessionId}")
    public Mono<ResponseEntity<ConversationSessionResponse>> getSession(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID sessionId) {

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

        return sessionService.updateSessionStatus(tenantId, sessionId, "completed")
                .map(ConversationSessionResponse::fromEntity)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping("/{sessionId}/archive")
    public Mono<ResponseEntity<ConversationSessionResponse>> archiveSession(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID sessionId) {

        return sessionService.updateSessionStatus(tenantId, sessionId, "archived")
                .map(ConversationSessionResponse::fromEntity)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
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
}
