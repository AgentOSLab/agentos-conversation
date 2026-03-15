package com.agentos.conversation.service;

import com.agentos.conversation.model.dto.CreateSessionRequest;
import com.agentos.conversation.model.entity.ConversationMessageEntity;
import com.agentos.conversation.model.entity.ConversationSessionEntity;
import com.agentos.conversation.repository.ConversationMessageRepository;
import com.agentos.conversation.repository.ConversationSessionRepository;
import com.agentos.common.model.PageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSessionService {

    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
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

        return sessionRepository.save(session);
    }

    public Mono<ConversationSessionEntity> getSession(UUID tenantId, UUID sessionId) {
        return sessionRepository.findByTenantIdAndId(tenantId, sessionId);
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
                    return sessionRepository.save(session);
                });
    }

    public Mono<ConversationSessionEntity> updateTitle(UUID tenantId, UUID sessionId, String title) {
        return sessionRepository.findByTenantIdAndId(tenantId, sessionId)
                .flatMap(session -> {
                    session.setTitle(title);
                    session.setUpdatedAt(OffsetDateTime.now());
                    return sessionRepository.save(session);
                });
    }

    public Mono<ConversationSessionEntity> updateMcpToolConfig(UUID tenantId, UUID sessionId,
                                                                Map<String, Object> config) {
        return sessionRepository.findByTenantIdAndId(tenantId, sessionId)
                .flatMap(session -> {
                    session.setMcpToolConfig(toJson(config));
                    session.setUpdatedAt(OffsetDateTime.now());
                    return sessionRepository.save(session);
                });
    }

    public Mono<ConversationMessageEntity> appendMessage(UUID sessionId, ConversationMessageEntity message) {
        message.setSessionId(sessionId);
        return messageRepository.save(message)
                .flatMap(saved -> sessionRepository.incrementMessageCount(sessionId, OffsetDateTime.now())
                        .thenReturn(saved));
    }

    public Mono<List<ConversationMessageEntity>> getRecentMessages(UUID sessionId, int limit) {
        return messageRepository.findRecentBySession(sessionId, limit)
                .collectList()
                .map(msgs -> {
                    java.util.Collections.reverse(msgs);
                    return msgs;
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
                .then();
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

    private String toJson(Map<String, Object> map) {
        if (map == null) return null;
        try { return objectMapper.writeValueAsString(map); }
        catch (JsonProcessingException e) { throw new RuntimeException(e); }
    }
}
