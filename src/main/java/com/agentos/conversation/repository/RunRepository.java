package com.agentos.conversation.repository;

import com.agentos.conversation.model.entity.RunEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface RunRepository extends ReactiveCrudRepository<RunEntity, UUID> {

    Mono<RunEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query("""
            SELECT * FROM runs
            WHERE session_id = :sessionId AND tenant_id = :tenantId
            ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
            """)
    Flux<RunEntity> findBySession(UUID sessionId, UUID tenantId, int limit, long offset);

    @Query("SELECT COUNT(*) FROM runs WHERE session_id = :sessionId AND tenant_id = :tenantId")
    Mono<Long> countBySession(UUID sessionId, UUID tenantId);

    @Modifying
    @Query("""
            UPDATE runs SET status = :status, started_at = :now
            WHERE id = :runId AND status = 'queued'
            """)
    Mono<Void> markRunning(UUID runId, OffsetDateTime now);

    @Modifying
    @Query("""
            UPDATE runs SET status = :status, completed_at = :now,
                            output_message_id = :outputMessageId,
                            token_usage = :tokenUsage,
                            last_event_id = :lastEventId
            WHERE id = :runId
            """)
    Mono<Void> completeRun(UUID runId, String status, UUID outputMessageId,
                           String tokenUsage, String lastEventId, OffsetDateTime now);

    @Modifying
    @Query("UPDATE runs SET status = :status WHERE id = :runId")
    Mono<Void> updateStatus(UUID runId, String status);

    @Modifying
    @Query("UPDATE runs SET task_id = :taskId WHERE id = :runId")
    Mono<Void> setTaskId(UUID runId, UUID taskId);

    @Modifying
    @Query("UPDATE runs SET error = :error, status = 'failed', completed_at = :now WHERE id = :runId")
    Mono<Void> failRun(UUID runId, String error, OffsetDateTime now);

    @Modifying
    @Query("UPDATE runs SET last_event_id = :lastEventId WHERE id = :runId")
    Mono<Void> updateLastEventId(UUID runId, String lastEventId);
}
