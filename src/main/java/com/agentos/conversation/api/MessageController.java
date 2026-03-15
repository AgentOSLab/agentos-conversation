package com.agentos.conversation.api;

import com.agentos.conversation.model.dto.SendMessageRequest;
import com.agentos.conversation.orchestration.MessageOrchestrator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Main message entry point. Accepts user messages, routes through
 * the orchestrator, and returns responses or SSE streams.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}")
@RequiredArgsConstructor
public class MessageController {

    private final MessageOrchestrator orchestrator;

    @PostMapping("/messages")
    public Mono<ResponseEntity<Map<String, Object>>> sendMessage(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody SendMessageRequest request) {

        return orchestrator.processMessage(tenantId, userId, sessionId, request.getContent())
                .map(ResponseEntity::ok);
    }

    @GetMapping(value = "/tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamTaskEvents(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @PathVariable UUID taskId) {

        return orchestrator.streamEvents(taskId, sessionId, tenantId, userId);
    }

    @PostMapping("/tasks/{taskId}/human-input")
    public Mono<ResponseEntity<Map<String, Object>>> submitHumanInput(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @PathVariable UUID taskId,
            @RequestBody Map<String, Object> input) {

        return orchestrator.processMessage(tenantId, userId, sessionId,
                        String.valueOf(input.getOrDefault("content", "")))
                .map(ResponseEntity::ok);
    }
}
