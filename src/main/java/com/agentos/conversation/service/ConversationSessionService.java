package com.agentos.conversation.service;

import com.agentos.conversation.model.dto.CreateSessionRequest;
import com.agentos.conversation.model.entity.ConversationMessageEntity;
import com.agentos.conversation.model.entity.ConversationSessionEntity;
import com.agentos.conversation.repository.ConversationMessageRepository;
import com.agentos.conversation.repository.ConversationSessionRepository;
import com.agentos.common.model.PageResponse;
import com.agentos.common.audit.AuditAction;
import com.agentos.common.audit.AuditClient;
import com.agentos.common.audit.AuditResourceType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSessionService {

    @Autowired(required = false)
    private AuditClient auditClient;

    /** Redis list key prefix for the per-session sliding message window. */
    private static final String SESSION_MSGS_KEY = "session:msgs:";

    /** Context-builder cache key prefix — invalidated on every message append. */
    private static final String CTX_RECENT_KEY = "ctx:recent:";

    @Value("${agentos.session.message-window-size:50}")
    private int messageWindowSize;

    @Value("${agentos.session.message-window-ttl-days:7}")
    private int messageWindowTtlDays;

    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Mono<ConversationSessionEntity> createSession(UUID tenantId, UUID userId, CreateSessionRequest request) {
        ConversationSessionEntity session = ConversationSessionEntity.builder()
                .tenantId(tenantId)
                .userId(userId)
                .sessionType(request.getSessionType())
                .interactionMode(request.getInteractionMode() != null ? request.getInteractionMode() : "INTERACTIVE")
                .boundEntityType(request.getBoundEntityType())
                .boundEntityId(request.getBoundEntityId())
                .title(request.getTitle())
                .mcpToolConfig(toJson(request.getMcpToolConfig()))
                .build();

        return sessionRepository.save(session)
                .doOnSuccess(s -> log.info("Session created: sessionId={} agentId={} tenant={}",
                        s.getId(), request.getBoundEntityId(), tenantId))
                .flatMap(s -> audit(tenantId, userId, AuditAction.SESSION_CREATE,
                        AuditResourceType.SESSION, s.getId().toString(),
                        Map.of("sessionType", request.getSessionType()))
                        .thenReturn(s));
    }

    public Mono<ConversationSessionEntity> getSession(UUID tenantId, UUID sessionId) {
        return sessionRepository.findByTenantIdAndId(tenantId, sessionId)
                .doOnSuccess(s -> { if (s != null) log.debug("Session retrieved: sessionId={}", sessionId); });
    }

    public Mono<PageResponse<ConversationSessionEntity>> listSessions(UUID tenantId, UUID userId,
                                                                      String status, String sessionType,
                                                                      int limit, long offset) {
        return sessionRepository.countByUser(tenantId, userId, status, sessionType)
                .flatMap(total -> sessionRepository.findByUser(tenantId, userId, status, sessionType, limit, offset)
                        .collectList()
                        .map(items -> PageResponse.of(items, null, total)));
    }

    public Mono<ConversationSessionEntity> updateSessionStatus(UUID tenantId, UUID sessionId, String status) {
        return sessionRepository.findByTenantIdAndId(tenantId, sessionId)
                .flatMap(session -> {
                    session.setStatus(status);
                    session.setUpdatedAt(OffsetDateTime.now());
                    return sessionRepository.save(session)
                            .doOnSuccess(s -> log.info("Session status updated: sessionId={} status={}", sessionId, status));
                })
                .flatMap(session -> {
                    String action = "completed".equals(status) ? AuditAction.SESSION_COMPLETE
                            : "archived".equals(status) ? AuditAction.SESSION_ARCHIVE
                            : "session.update_status";
                    return audit(tenantId, session.getUserId(), action,
                            AuditResourceType.SESSION, sessionId.toString(),
                            Map.of("status", status))
                            .thenReturn(session);
                });
    }

    public Mono<ConversationSessionEntity> updateTitle(UUID tenantId, UUID sessionId, String title) {
        return sessionRepository.findByTenantIdAndId(tenantId, sessionId)
                .flatMap(session -> {
                    session.setTitle(title);
                    session.setUpdatedAt(OffsetDateTime.now());
                    return sessionRepository.save(session);
                })
                .flatMap(session -> audit(tenantId, session.getUserId(), AuditAction.SESSION_UPDATE_TITLE,
                        AuditResourceType.SESSION, sessionId.toString(),
                        Map.of("title", title))
                        .thenReturn(session));
    }

    public Mono<ConversationSessionEntity> updateMcpToolConfig(UUID tenantId, UUID sessionId,
                                                                Map<String, Object> config) {
        return sessionRepository.findByTenantIdAndId(tenantId, sessionId)
                .flatMap(session -> {
                    session.setMcpToolConfig(toJson(config));
                    session.setUpdatedAt(OffsetDateTime.now());
                    return sessionRepository.save(session);
                })
                .flatMap(session -> audit(tenantId, session.getUserId(), AuditAction.SESSION_UPDATE_MCP,
                        AuditResourceType.SESSION, sessionId.toString(), null)
                        .thenReturn(session));
    }

    /**
     * Persist a new message to the DB, push it to the Redis sliding window list,
     * and invalidate the context-builder's read-through cache so the next
     * context assembly always sees fresh data.
     *
     * Redis list key: {@code session:msgs:{sessionId}}
     * Window size:    {@code agentos.session.message-window-size} (default 50)
     * TTL:            {@code agentos.session.message-window-ttl-days} (default 7 days)
     */
    public Mono<ConversationMessageEntity> appendMessage(UUID sessionId, ConversationMessageEntity message) {
        message.setSessionId(sessionId);
        return messageRepository.save(message)
                .flatMap(saved ->
                        sessionRepository.incrementMessageCount(sessionId, OffsetDateTime.now())
                                .then(pushToRedisWindow(sessionId, saved))
                                .then(audit(null, null, AuditAction.MESSAGE_SEND,
                                        AuditResourceType.MESSAGE, saved.getId().toString(),
                                        Map.of("sessionId", sessionId.toString(),
                                                "role", String.valueOf(saved.getRole()))))
                                .thenReturn(saved));
    }

    /**
     * Push the serialized message to the right end of the Redis list,
     * trim the list to the configured window size, refresh the TTL,
     * and invalidate the context-builder's separate read-through cache entry.
     * Errors are logged and suppressed so a Redis failure never blocks message persistence.
     */
    private Mono<Void> pushToRedisWindow(UUID sessionId, ConversationMessageEntity saved) {
        String listKey = SESSION_MSGS_KEY + sessionId;
        String ctxKey  = CTX_RECENT_KEY + sessionId;
        try {
            String json = objectMapper.writeValueAsString(saved);
            return redisTemplate.opsForList().rightPush(listKey, json)
                    .flatMap(size -> redisTemplate.opsForList().trim(listKey, -messageWindowSize, -1))
                    .then(redisTemplate.expire(listKey, Duration.ofDays(messageWindowTtlDays)))
                    .then(redisTemplate.delete(ctxKey))
                    .then()
                    .onErrorResume(e -> {
                        log.warn("Redis window push failed for session {}: {}", sessionId, e.getMessage());
                        return Mono.empty();
                    });
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize message for Redis window, session {}: {}", sessionId, e.getMessage());
            return Mono.empty();
        }
    }

    /**
     * Returns the most recent {@code limit} messages for the session in chronological order.
     *
     * Read path:
     *   1. Redis sliding window list ({@code session:msgs:{sessionId}}) — O(1) tail read, no DB hit
     *   2. PostgreSQL fallback if the Redis list is absent or deserialization fails
     */
    public Mono<List<ConversationMessageEntity>> getRecentMessages(UUID sessionId, int limit) {
        String listKey = SESSION_MSGS_KEY + sessionId;
        return redisTemplate.opsForList().range(listKey, -limit, -1)
                .collectList()
                .flatMap(jsonList -> {
                    if (!jsonList.isEmpty()) {
                        try {
                            List<ConversationMessageEntity> messages = new ArrayList<>(jsonList.size());
                            for (String json : jsonList) {
                                messages.add(objectMapper.readValue(json, ConversationMessageEntity.class));
                            }
                            log.debug("Redis window hit: {} messages for session {}", messages.size(), sessionId);
                            return Mono.just(messages);
                        } catch (Exception e) {
                            log.debug("Redis window deserialization failed for session {}, falling back to DB", sessionId);
                        }
                    }
                    // DB fallback: findRecentBySession returns newest-first, so reverse for chronological order
                    return messageRepository.findRecentBySession(sessionId, limit)
                            .collectList()
                            .map(msgs -> {
                                java.util.Collections.reverse(msgs);
                                return msgs;
                            });
                });
    }

    public Mono<List<ConversationMessageEntity>> getPinnedMessages(UUID sessionId) {
        return messageRepository.findPinnedBySession(sessionId).collectList();
    }

    public Mono<PageResponse<ConversationMessageEntity>> getMessagesPaged(UUID sessionId, int limit, long offset) {
        return messageRepository.countBySessionId(sessionId)
                .flatMap(total -> messageRepository.findBySessionPaged(sessionId, limit, offset)
                        .collectList()
                        .map(items -> PageResponse.of(items, null, total)));
    }

    public Mono<Void> pinMessage(UUID messageId, boolean pinned) {
        return messageRepository.findById(messageId)
                .flatMap(msg -> {
                    msg.setPinned(pinned);
                    return messageRepository.save(msg);
                })
                .then(audit(null, null, AuditAction.MESSAGE_PIN,
                        AuditResourceType.MESSAGE, messageId.toString(),
                        Map.of("pinned", pinned)));
    }

    public Mono<Void> addTokenUsage(UUID sessionId, long tokens) {
        return sessionRepository.addTokens(sessionId, tokens, OffsetDateTime.now());
    }

    public Mono<Void> updateConversationSummary(UUID sessionId, String summary) {
        return sessionRepository.updateSummary(sessionId, summary, OffsetDateTime.now());
    }

    public Mono<Void> incrementTaskCount(UUID sessionId) {
        return sessionRepository.incrementTaskCount(sessionId, OffsetDateTime.now());
    }

    public Mono<ConversationMessageEntity> getMessageById(UUID messageId) {
        return messageRepository.findById(messageId);
    }

    /**
     * Returns summaries from the user's most-recently-active sessions (excluding the current one),
     * for cross-session memory context injection.
     */
    public Mono<List<String>> getPastSessionSummaries(UUID tenantId, UUID userId,
                                                       UUID excludeSessionId, int limit) {
        return sessionRepository.findRecentSummaries(tenantId, userId, excludeSessionId, limit)
                .collectList();
    }

    private Mono<Void> audit(UUID tenantId, UUID actorId, String action,
                              String resourceType, String resourceId,
                              Map<String, Object> details) {
        if (auditClient == null) return Mono.empty();
        return auditClient.record(tenantId, actorId, action, resourceType, resourceId, "success", details);
    }

    private String toJson(Map<String, Object> map) {
        if (map == null) return null;
        try { return objectMapper.writeValueAsString(map); }
        catch (JsonProcessingException e) { throw new RuntimeException(e); }
    }
}
