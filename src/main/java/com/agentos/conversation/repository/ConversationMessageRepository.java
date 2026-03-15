package com.agentos.conversation.repository;

import com.agentos.conversation.model.entity.ConversationMessageEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ConversationMessageRepository extends ReactiveCrudRepository<ConversationMessageEntity, UUID> {

    @Query("""
            SELECT * FROM conversation_messages
            WHERE session_id = :sessionId
            ORDER BY created_at DESC
            LIMIT :limit
            """)
    Flux<ConversationMessageEntity> findRecentBySession(UUID sessionId, int limit);

    @Query("""
            SELECT * FROM conversation_messages
            WHERE session_id = :sessionId
              AND pinned = TRUE
            ORDER BY created_at ASC
            """)
    Flux<ConversationMessageEntity> findPinnedBySession(UUID sessionId);

    @Query("""
            SELECT * FROM conversation_messages
            WHERE session_id = :sessionId
            ORDER BY created_at ASC
            LIMIT :limit OFFSET :offset
            """)
    Flux<ConversationMessageEntity> findBySessionPaged(UUID sessionId, int limit, long offset);

    Mono<Long> countBySessionId(UUID sessionId);

    Flux<ConversationMessageEntity> findByTaskId(UUID taskId);
}
