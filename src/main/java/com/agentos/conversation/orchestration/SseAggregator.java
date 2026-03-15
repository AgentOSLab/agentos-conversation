package com.agentos.conversation.orchestration;

import com.agentos.conversation.integration.AgentRuntimeClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregates SSE event streams from Agent Runtime, transforming raw
 * task-level events into conversation-level events for the frontend.
 * Adds session metadata and normalizes event formats.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseAggregator {

    private final AgentRuntimeClient agentRuntimeClient;
    private final ObjectMapper objectMapper;

    public Flux<String> streamTaskAsConversationEvents(UUID taskId, UUID sessionId,
                                                        UUID tenantId, UUID userId) {
        return agentRuntimeClient.streamTaskEvents(taskId, tenantId, userId)
                .map(rawEvent -> enrichWithSessionContext(rawEvent, sessionId))
                .onErrorResume(e -> {
                    log.error("SSE aggregation error for task {}: {}", taskId, e.getMessage());
                    return Flux.just(buildErrorEvent(e.getMessage(), sessionId));
                });
    }

    private String enrichWithSessionContext(String rawEvent, UUID sessionId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = objectMapper.readValue(rawEvent, Map.class);
            event.put("sessionId", sessionId.toString());
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return rawEvent;
        }
    }

    private String buildErrorEvent(String message, UUID sessionId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "error");
        event.put("sessionId", sessionId.toString());
        event.put("message", message);
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"error\",\"message\":\"" + message + "\"}";
        }
    }
}
