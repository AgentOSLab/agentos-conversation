package com.agentos.conversation.service;

import com.agentos.conversation.repository.ConversationSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionArchivalJobTest {

    @Mock ConversationSessionRepository sessionRepository;

    SessionArchivalJob job;

    @BeforeEach
    void setUp() {
        job = new SessionArchivalJob(sessionRepository);
        ReflectionTestUtils.setField(job, "inactiveDays", 30);
        ReflectionTestUtils.setField(job, "batchSize", 500);
    }

    @Nested
    class ArchiveInactiveSessions {

        @Test
        void noInactiveSessions_completesWithoutArchiving() {
            when(sessionRepository.findActiveSessionsOlderThan(any(OffsetDateTime.class), eq(500)))
                    .thenReturn(Flux.empty());

            assertThatNoException().isThrownBy(() -> job.archiveInactiveSessions());

            verify(sessionRepository).findActiveSessionsOlderThan(any(OffsetDateTime.class), eq(500));
            verify(sessionRepository, never()).save(any());
        }

        @Test
        void configDefaults_areApplied() {
            int inactiveDays = (int) ReflectionTestUtils.getField(job, "inactiveDays");
            int batchSize = (int) ReflectionTestUtils.getField(job, "batchSize");

            assertThatNoException().isThrownBy(() -> {
                assert inactiveDays == 30;
                assert batchSize == 500;
            });
        }

        @Test
        void customConfig_isRespected() {
            ReflectionTestUtils.setField(job, "inactiveDays", 7);
            ReflectionTestUtils.setField(job, "batchSize", 100);

            when(sessionRepository.findActiveSessionsOlderThan(any(OffsetDateTime.class), eq(100)))
                    .thenReturn(Flux.empty());

            assertThatNoException().isThrownBy(() -> job.archiveInactiveSessions());

            verify(sessionRepository).findActiveSessionsOlderThan(any(OffsetDateTime.class), eq(100));
        }
    }
}
