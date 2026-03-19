package com.agentos.conversation.context;

import com.agentos.conversation.integration.LlmGatewayClient;
import com.agentos.conversation.model.entity.ConversationMessageEntity;
import com.agentos.conversation.model.entity.ConversationSessionEntity;
import com.agentos.conversation.service.ConversationSessionService;
import com.agentos.common.model.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationSummarizerTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private ConversationSessionService sessionService;
    @Mock
    private LlmGatewayClient llmGatewayClient;
    @Mock
    private ModelContextService modelContextService;
    @Mock
    private ReactiveStringRedisTemplate redisTemplate;
    @Mock
    private ReactiveValueOperations<String, String> valueOps;
    @Mock
    private SessionContextBuilder contextBuilder;

    private ConversationSummarizer summarizer;

    @BeforeEach
    void setUp() {
        summarizer = new ConversationSummarizer(
                sessionService, llmGatewayClient, modelContextService, redisTemplate, contextBuilder);

        ReflectionTestUtils.setField(summarizer, "summarizerLockTtlSeconds", 90);
        ReflectionTestUtils.setField(summarizer, "summarizationThreshold", 0.80);
        ReflectionTestUtils.setField(summarizer, "summaryTokenRatio", 0.07);
        ReflectionTestUtils.setField(summarizer, "summaryTokenMin", 800);
        ReflectionTestUtils.setField(summarizer, "summaryTokenMax", 10000);
        ReflectionTestUtils.setField(summarizer, "windowSize", 20);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void summarizeIfNeeded_underThreshold_returnsFalse() {
        when(modelContextService.getContextWindow(TENANT_ID)).thenReturn(Mono.just(128000));
        ConversationSessionEntity session = ConversationSessionEntity.builder()
                .id(SESSION_ID)
                .tenantId(TENANT_ID)
                .messageCount(10)
                .conversationSummary(null)
                .build();
        when(sessionService.getSession(TENANT_ID, SESSION_ID)).thenReturn(Mono.just(session));

        StepVerifier.create(summarizer.summarizeIfNeeded(SESSION_ID, TENANT_ID))
                .expectNext(false)
                .verifyComplete();

        verify(sessionService, never()).getMessagesPaged(any(), anyInt(), anyLong());
        verify(llmGatewayClient, never()).chat(any());
    }

    @Test
    void summarizeIfNeeded_overThreshold_triggersAndReturnsTrue() {
        when(modelContextService.getContextWindow(TENANT_ID)).thenReturn(Mono.just(128000));
        ConversationSessionEntity session = ConversationSessionEntity.builder()
                .id(SESSION_ID)
                .tenantId(TENANT_ID)
                .messageCount(600)
                .conversationSummary(null)
                .build();
        when(sessionService.getSession(TENANT_ID, SESSION_ID)).thenReturn(Mono.just(session));

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));

        ConversationMessageEntity msg = ConversationMessageEntity.builder()
                .id(UUID.randomUUID())
                .sessionId(SESSION_ID)
                .role("user")
                .content("Test message")
                .build();
        PageResponse<ConversationMessageEntity> page = PageResponse.of(List.of(msg), null, 1);
        when(sessionService.getMessagesPaged(eq(SESSION_ID), eq(580), eq(0L)))
                .thenReturn(Mono.just(page));

        when(llmGatewayClient.chat(any()))
                .thenReturn(Mono.just(Map.of(
                        "choices", List.of(Map.of("message", Map.of("content", "Summary text"))))));

        when(sessionService.updateConversationSummary(eq(SESSION_ID), eq("Summary text")))
                .thenReturn(Mono.empty());
        when(contextBuilder.invalidateSessionCache(SESSION_ID)).thenReturn(Mono.empty());

        StepVerifier.create(summarizer.summarizeIfNeeded(SESSION_ID, TENANT_ID))
                .expectNext(true)
                .verifyComplete();

        verify(sessionService).updateConversationSummary(eq(SESSION_ID), eq("Summary text"));
        verify(contextBuilder).invalidateSessionCache(SESSION_ID);
    }

    @Test
    void summarizeIfNeeded_lockNotAcquired_returnsFalse() {
        when(modelContextService.getContextWindow(TENANT_ID)).thenReturn(Mono.just(128000));
        ConversationSessionEntity session = ConversationSessionEntity.builder()
                .id(SESSION_ID)
                .tenantId(TENANT_ID)
                .messageCount(600)
                .conversationSummary(null)
                .build();
        when(sessionService.getSession(TENANT_ID, SESSION_ID)).thenReturn(Mono.just(session));

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(false));

        StepVerifier.create(summarizer.summarizeIfNeeded(SESSION_ID, TENANT_ID))
                .expectNext(false)
                .verifyComplete();

        verify(sessionService, never()).getMessagesPaged(any(), anyInt(), anyLong());
        verify(llmGatewayClient, never()).chat(any());
    }

    @Test
    void computeSummaryMaxTokens_normalRange() {
        int result = summarizer.computeSummaryMaxTokens(128000);
        assertThat(result).isEqualTo(8960); // 0.07 * 128000 = 8960, within [800, 10000]
    }

    @Test
    void computeSummaryMaxTokens_belowMin() {
        int result = summarizer.computeSummaryMaxTokens(5000);
        assertThat(result).isEqualTo(800); // 0.07 * 5000 = 350, clamped to 800
    }

    @Test
    void computeSummaryMaxTokens_aboveMax() {
        int result = summarizer.computeSummaryMaxTokens(200000);
        assertThat(result).isEqualTo(10000); // 0.07 * 200000 = 14000, clamped to 10000
    }

    @Test
    void summarizeIfNeeded_excessMessagesZero_returnsFalse() {
        when(modelContextService.getContextWindow(TENANT_ID)).thenReturn(Mono.just(128000));
        String longSummary = "x".repeat(400000); // ~100k tokens; 15*200 + 100000 = 103000 >= 102400
        ConversationSessionEntity session = ConversationSessionEntity.builder()
                .id(SESSION_ID)
                .tenantId(TENANT_ID)
                .messageCount(15)
                .conversationSummary(longSummary)
                .build();
        when(sessionService.getSession(TENANT_ID, SESSION_ID)).thenReturn(Mono.just(session));

        StepVerifier.create(summarizer.summarizeIfNeeded(SESSION_ID, TENANT_ID))
                .expectNext(false)
                .verifyComplete();

        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(sessionService, never()).getMessagesPaged(any(), anyInt(), anyLong());
    }

}
