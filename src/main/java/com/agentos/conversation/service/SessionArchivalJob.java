package com.agentos.conversation.service;

import com.agentos.conversation.repository.ConversationSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nightly job that archives conversation sessions inactive beyond the configured threshold.
 *
 * A session is eligible for archival when:
 *   - Its {@code status} is {@code active}
 *   - Its {@code last_activity_at} timestamp is older than {@code agentos.session.archival.inactive-days}
 *
 * Archiving sets {@code status = 'archived'} without deleting any data, preserving full
 * conversation history and summaries for audit and cross-session memory retrieval.
 *
 * Configuration:
 *   agentos.session.archival.inactive-days  (default: 30)  — inactivity threshold in days
 *   agentos.session.archival.batch-size     (default: 500) — max sessions archived per run
 *   agentos.session.archival.cron           (default: daily at 02:00) — cron expression
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionArchivalJob {

    @Value("${agentos.session.archival.inactive-days:30}")
    private int inactiveDays;

    @Value("${agentos.session.archival.batch-size:500}")
    private int batchSize;

    private final ConversationSessionRepository sessionRepository;

    @Scheduled(cron = "${agentos.session.archival.cron:0 0 2 * * *}")
    public void archiveInactiveSessions() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(inactiveDays);
        log.info("Session archival job started: inactiveDays={}, cutoff={}, batchSize={}",
                inactiveDays, cutoff, batchSize);

        AtomicInteger archivedCount = new AtomicInteger(0);

        sessionRepository.findActiveSessionsOlderThan(cutoff, batchSize)
                .flatMap(session -> {
                    session.setStatus("archived");
                    session.setUpdatedAt(OffsetDateTime.now());
                    return sessionRepository.save(session)
                            .doOnSuccess(s -> {
                                archivedCount.incrementAndGet();
                                log.debug("Archived session: id={}, lastActivity={}",
                                        s.getId(), s.getLastActivityAt());
                            })
                            .onErrorResume(e -> {
                                log.warn("Failed to archive session {}: {}", session.getId(), e.getMessage());
                                return reactor.core.publisher.Mono.empty();
                            });
                })
                .doOnComplete(() ->
                        log.info("Session archival job completed: {} sessions archived", archivedCount.get()))
                .doOnError(e ->
                        log.error("Session archival job failed: {}", e.getMessage(), e))
                .subscribe();
    }
}
