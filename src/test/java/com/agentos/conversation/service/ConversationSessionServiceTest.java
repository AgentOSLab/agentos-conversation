package com.agentos.conversation.service;

import com.agentos.conversation.model.entity.ConversationMessageEntity;
import com.agentos.conversation.model.entity.ConversationSessionEntity;
import com.agentos.conversation.repository.ConversationMessageRepository;
import com.agentos.conversation.repository.ConversationSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConversationSessionService.
 *
 * Covers:
 * - appendMessage: DB save, Redis RPUSH + LTRIM, TTL refresh, ctx cache invalidation
 * - getRecentMessages: Redis hit path, DB fallback when Redis empty
 * - getPastSessionSummaries: delegates to findRecentSummaries
 * - Redis errors in appendMessage are suppressed (never block message persistence)
 */
@ExtendWith(MockitoExtension.class)
class ConversationSessionServiceTest {

    @Mock private ConversationSessionRepository sessionRepository;
    @Mock private ConversationMessageRepository messageRepository;
    @Mock private ReactiveStringRedisTemplate redisTemplate;
    @Mock private ReactiveListOperations<String, String> listOps;

    private ConversationSessionService service;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID TENANT_ID  = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID    = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        service = new ConversationSessionService(
                sessionRepository, messageRepository, redisTemplate, objectMapper);
        ReflectionTestUtils.setField(service, "messageWindowSize", 50);
        ReflectionTestUtils.setField(service, "messageWindowTtlDays", 7);

        lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
        // pushToRedisWindow builds .then(redisTemplate.expire(...)) eagerly — must never be null.
        lenient().when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        lenient().when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ConversationMessageEntity buildMessage(String role, String content) {
        return ConversationMessageEntity.builder()
                .id(UUID.randomUUID())
                .sessionId(SESSION_ID)
                .role(role)
                .content(content)
                .build();
    }

    private void stubRedisWindowPush(boolean succeed) {
        if (succeed) {
            when(listOps.rightPush(anyString(), anyString())).thenReturn(Mono.just(1L));
            when(listOps.trim(anyString(), anyLong(), anyLong())).thenReturn(Mono.just(true));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
            when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
        } else {
            when(listOps.rightPush(anyString(), anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis down")));
        }
    }

    // ── appendMessage ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("appendMessage")
    class AppendMessageTests {

        @Test
        @DisplayName("persists message to DB and pushes to Redis window")
        void appendMessage_savesAndPushesToRedis() {
            ConversationMessageEntity message = buildMessage("user", "Hello");
            ConversationMessageEntity saved = buildMessage("user", "Hello");

            when(messageRepository.save(any())).thenReturn(Mono.just(saved));
            when(sessionRepository.incrementMessageCount(eq(SESSION_ID), any(OffsetDateTime.class)))
                    .thenReturn(Mono.empty());
            stubRedisWindowPush(true);

            StepVerifier.create(service.appendMessage(SESSION_ID, message))
                    .assertNext(msg -> assertThat(msg.getContent()).isEqualTo("Hello"))
                    .verifyComplete();

            verify(messageRepository).save(any());
            verify(listOps).rightPush(eq("session:msgs:" + SESSION_ID), anyString());
            verify(listOps).trim(eq("session:msgs:" + SESSION_ID), eq(-50L), eq(-1L));
            verify(redisTemplate).expire(eq("session:msgs:" + SESSION_ID), eq(Duration.ofDays(7)));
        }

        @Test
        @DisplayName("invalidates context-builder cache (deletes ctx:recent:{sessionId}) on append")
        void appendMessage_invalidatesContextCache() {
            ConversationMessageEntity message = buildMessage("assistant", "Done.");
            ConversationMessageEntity saved = buildMessage("assistant", "Done.");

            when(messageRepository.save(any())).thenReturn(Mono.just(saved));
            when(sessionRepository.incrementMessageCount(eq(SESSION_ID), any(OffsetDateTime.class)))
                    .thenReturn(Mono.empty());
            stubRedisWindowPush(true);

            StepVerifier.create(service.appendMessage(SESSION_ID, message))
                    .assertNext(msg -> assertThat(msg).isNotNull())
                    .verifyComplete();

            verify(redisTemplate).delete(eq("ctx:recent:" + SESSION_ID));
        }

        @Test
        @DisplayName("Redis failure during window push is suppressed and DB-saved message is returned")
        void appendMessage_redisFailureSuppressed() {
            ConversationMessageEntity message = buildMessage("user", "Test");
            ConversationMessageEntity saved = buildMessage("user", "Test");

            when(messageRepository.save(any())).thenReturn(Mono.just(saved));
            when(sessionRepository.incrementMessageCount(eq(SESSION_ID), any(OffsetDateTime.class)))
                    .thenReturn(Mono.empty());
            stubRedisWindowPush(false);

            // Redis error must NOT propagate — message is still returned
            StepVerifier.create(service.appendMessage(SESSION_ID, message))
                    .assertNext(msg -> assertThat(msg.getContent()).isEqualTo("Test"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("sets sessionId on the message before saving")
        void appendMessage_setsSessionId() {
            ConversationMessageEntity message = ConversationMessageEntity.builder()
                    .role("user").content("Hi").build(); // no sessionId yet
            ConversationMessageEntity saved = buildMessage("user", "Hi");

            when(messageRepository.save(any())).thenReturn(Mono.just(saved));
            when(sessionRepository.incrementMessageCount(eq(SESSION_ID), any(OffsetDateTime.class)))
                    .thenReturn(Mono.empty());
            stubRedisWindowPush(true);

            service.appendMessage(SESSION_ID, message).block();

            ArgumentCaptor<ConversationMessageEntity> captor =
                    ArgumentCaptor.forClass(ConversationMessageEntity.class);
            verify(messageRepository).save(captor.capture());
            assertThat(captor.getValue().getSessionId()).isEqualTo(SESSION_ID);
        }
    }

    // ── getRecentMessages ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("getRecentMessages")
    class GetRecentMessagesTests {

        @Test
        @DisplayName("returns messages from Redis when window list is non-empty (cache hit)")
        void getRecentMessages_redisHit() throws Exception {
            ConversationMessageEntity msg1 = buildMessage("user", "Hello");
            ConversationMessageEntity msg2 = buildMessage("assistant", "Hi there");

            String json1 = objectMapper.writeValueAsString(msg1);
            String json2 = objectMapper.writeValueAsString(msg2);

            when(listOps.range(eq("session:msgs:" + SESSION_ID), eq(-10L), eq(-1L)))
                    .thenReturn(Flux.just(json1, json2));

            StepVerifier.create(service.getRecentMessages(SESSION_ID, 10))
                    .assertNext(messages -> {
                        assertThat(messages).hasSize(2);
                        assertThat(messages.get(0).getContent()).isEqualTo("Hello");
                        assertThat(messages.get(1).getContent()).isEqualTo("Hi there");
                    })
                    .verifyComplete();

            verifyNoInteractions(messageRepository);
        }

        @Test
        @DisplayName("falls back to DB when Redis list is empty")
        void getRecentMessages_redisMiss_fallsBackToDb() {
            ConversationMessageEntity dbMsg = buildMessage("user", "DB message");

            when(listOps.range(anyString(), anyLong(), anyLong()))
                    .thenReturn(Flux.empty());
            // DB returns newest-first; service reverses it
            when(messageRepository.findRecentBySession(SESSION_ID, 5))
                    .thenReturn(Flux.just(buildMessage("assistant", "Second"), dbMsg));

            StepVerifier.create(service.getRecentMessages(SESSION_ID, 5))
                    .assertNext(messages -> {
                        assertThat(messages).hasSize(2);
                        // reversed: dbMsg first
                        assertThat(messages.get(0).getContent()).isEqualTo("DB message");
                        assertThat(messages.get(1).getContent()).isEqualTo("Second");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("falls back to DB when Redis entries cannot be deserialized")
        void getRecentMessages_deserializationError_fallsBackToDb() {
            when(listOps.range(anyString(), anyLong(), anyLong()))
                    .thenReturn(Flux.just("not-valid-json"));
            when(messageRepository.findRecentBySession(SESSION_ID, 3))
                    .thenReturn(Flux.just(buildMessage("user", "Fallback")));

            StepVerifier.create(service.getRecentMessages(SESSION_ID, 3))
                    .assertNext(messages -> {
                        assertThat(messages).hasSize(1);
                        assertThat(messages.get(0).getContent()).isEqualTo("Fallback");
                    })
                    .verifyComplete();
        }
    }

    // ── getPastSessionSummaries ───────────────────────────────────────────────

    @Nested
    @DisplayName("getPastSessionSummaries")
    class GetPastSessionSummariesTests {

        @Test
        @DisplayName("returns list of summaries from repository")
        void returnsSummaries() {
            UUID excludeId = UUID.randomUUID();
            when(sessionRepository.findRecentSummaries(TENANT_ID, USER_ID, excludeId, 3))
                    .thenReturn(Flux.just("Summary A", "Summary B"));

            StepVerifier.create(service.getPastSessionSummaries(TENANT_ID, USER_ID, excludeId, 3))
                    .assertNext(summaries -> {
                        assertThat(summaries).containsExactly("Summary A", "Summary B");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("returns empty list when no summaries available")
        void returnsEmptyList() {
            UUID excludeId = UUID.randomUUID();
            when(sessionRepository.findRecentSummaries(TENANT_ID, USER_ID, excludeId, 5))
                    .thenReturn(Flux.empty());

            StepVerifier.create(service.getPastSessionSummaries(TENANT_ID, USER_ID, excludeId, 5))
                    .assertNext(summaries -> assertThat(summaries).isEmpty())
                    .verifyComplete();
        }
    }
}
