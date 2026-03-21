package com.agentos.conversation.repository;

import com.agentos.conversation.model.entity.ConversationSessionEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface ConversationSessionRepository extends ReactiveCrudRepository<ConversationSessionEntity, UUID> {

    Mono<ConversationSessionEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    /**
     * Fetch a session belonging to a specific user — used for user-scoped per-session operations
     * (ABAC ownership check at the data layer: tenantId + sessionId + userId must all match).
     */
    @Query("""
            SELECT * FROM conversation_sessions
            WHERE tenant_id = :tenantId
              AND id = :id
              AND user_id = :userId
            """)
    Mono<ConversationSessionEntity> findByTenantIdAndIdAndUserId(UUID tenantId, UUID id, UUID userId);

    @Query("""
            SELECT * FROM conversation_sessions
            WHERE tenant_id = :tenantId
              AND user_id = :userId
              AND (:status IS NULL OR status = :status)
              AND (:sessionType IS NULL OR session_type = :sessionType)
            ORDER BY last_activity_at DESC
            LIMIT :limit OFFSET :offset
            """)
    Flux<ConversationSessionEntity> findByUser(UUID tenantId, UUID userId,
                                               String status, String sessionType,
                                               int limit, long offset);

    @Query("""
            SELECT COUNT(*) FROM conversation_sessions
            WHERE tenant_id = :tenantId
              AND user_id = :userId
              AND (:status IS NULL OR status = :status)
              AND (:sessionType IS NULL OR session_type = :sessionType)
            """)
    Mono<Long> countByUser(UUID tenantId, UUID userId, String status, String sessionType);

    @Modifying
    @Query("""
            UPDATE conversation_sessions
            SET last_activity_at = :now,
                updated_at = :now,
                message_count = message_count + 1
            WHERE id = :sessionId
            """)
    Mono<Void> incrementMessageCount(UUID sessionId, OffsetDateTime now);

    @Modifying
    @Query("""
            UPDATE conversation_sessions
            SET last_activity_at = :now,
                updated_at = :now,
                task_count = task_count + 1
            WHERE id = :sessionId
            """)
    Mono<Void> incrementTaskCount(UUID sessionId, OffsetDateTime now);

    @Modifying
    @Query("""
            UPDATE conversation_sessions
            SET total_tokens = total_tokens + :tokens,
                updated_at = :now
            WHERE id = :sessionId
            """)
    Mono<Void> addTokens(UUID sessionId, long tokens, OffsetDateTime now);

    @Modifying
    @Query("""
            UPDATE conversation_sessions
            SET conversation_summary = :summary,
                updated_at = :now
            WHERE id = :sessionId
            """)
    Mono<Void> updateSummary(UUID sessionId, String summary, OffsetDateTime now);

    /**
     * Find active sessions whose last activity predates the given cutoff,
     * used by the nightly session archival job.
     */
    @Query("""
            SELECT * FROM conversation_sessions
            WHERE status = 'active'
              AND last_activity_at < :cutoff
            ORDER BY last_activity_at ASC
            LIMIT :limit
            """)
    Flux<ConversationSessionEntity> findActiveSessionsOlderThan(OffsetDateTime cutoff, int limit);

    /**
     * Fetch up to {@code limit} non-empty conversation summaries for a user,
     * excluding the current session, ordered by most recent activity.
     * Used to inject cross-session memory context.
     */
    @Query("""
            SELECT conversation_summary FROM conversation_sessions
            WHERE tenant_id = :tenantId
              AND user_id = :userId
              AND id != :excludeSessionId
              AND conversation_summary IS NOT NULL
              AND conversation_summary != ''
            ORDER BY last_activity_at DESC
            LIMIT :limit
            """)
    Flux<String> findRecentSummaries(UUID tenantId, UUID userId, UUID excludeSessionId, int limit);
}
