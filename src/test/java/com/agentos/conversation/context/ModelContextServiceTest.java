package com.agentos.conversation.context;

import com.agentos.conversation.integration.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelContextServiceTest {

    @Mock LlmGatewayClient llmGatewayClient;
    @Mock ReactiveStringRedisTemplate redisTemplate;
    @Mock ReactiveValueOperations<String, String> valueOps;

    ObjectMapper objectMapper = new ObjectMapper();
    ModelContextService modelContextService;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        modelContextService = new ModelContextService(llmGatewayClient, redisTemplate, objectMapper);
        ReflectionTestUtils.setField(modelContextService, "cacheTtlSeconds", 300);
        ReflectionTestUtils.setField(modelContextService, "defaultContextWindow", 128000);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(Mono.empty());
        when(valueOps.set(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(Mono.just(true));
        when(llmGatewayClient.listModels(any())).thenReturn(Mono.just(List.of()));
    }

    @Test
    void getContextWindow_cacheMiss_fetchesFromGateway() {
        when(valueOps.get(anyString())).thenReturn(Mono.empty());
        when(llmGatewayClient.listModels(any())).thenReturn(Mono.just(List.of(
                Map.<String, Object>of("id", "gpt-4o", "contextWindow", 128000),
                Map.<String, Object>of("id", "gpt-3.5", "contextWindow", 16000)
        )));

        StepVerifier.create(modelContextService.getContextWindow(TENANT_ID))
                .assertNext(window -> assertThat(window).isEqualTo(128000))
                .verifyComplete();

        verify(llmGatewayClient).listModels(TENANT_ID);
    }

    @Test
    void getContextWindow_cacheHit_usesRedis() throws Exception {
        List<Map<String, Object>> models = List.of(
                Map.<String, Object>of("id", "gpt-4o", "contextWindow", 128000),
                Map.<String, Object>of("id", "gpt-3.5", "contextWindow", 16000)
        );
        String cachedJson = objectMapper.writeValueAsString(models);
        String cacheKey = "model_ctx:" + TENANT_ID;
        doReturn(Mono.just(cachedJson)).when(valueOps).get(cacheKey);

        StepVerifier.create(modelContextService.getContextWindow(TENANT_ID))
                .assertNext(window -> assertThat(window).isEqualTo(128000))
                .verifyComplete();
    }

    @Test
    void getContextWindow_noModels_returnsDefault() {
        when(valueOps.get(anyString())).thenReturn(Mono.empty());
        when(llmGatewayClient.listModels(any())).thenReturn(Mono.just(List.of()));

        StepVerifier.create(modelContextService.getContextWindow(TENANT_ID))
                .assertNext(window -> assertThat(window).isEqualTo(128000))
                .verifyComplete();
    }

    @Test
    void getModelInfo_found_returnsModel() throws Exception {
        List<Map<String, Object>> models = List.of(
                Map.<String, Object>of("id", "gpt-4o", "contextWindow", 128000),
                Map.<String, Object>of("id", "gpt-3.5", "contextWindow", 16000)
        );
        String cachedJson = objectMapper.writeValueAsString(models);
        String cacheKey = "model_ctx:" + TENANT_ID;
        doReturn(Mono.just(cachedJson)).when(valueOps).get(cacheKey);

        StepVerifier.create(modelContextService.getModelInfo("gpt-4o", TENANT_ID))
                .assertNext(info -> {
                    assertThat(info).containsEntry("id", "gpt-4o");
                    assertThat(info).containsEntry("contextWindow", 128000);
                })
                .verifyComplete();
    }

    @Test
    void getModelInfo_notFound_returnsEmptyMap() throws Exception {
        List<Map<String, Object>> models = List.of(
                Map.<String, Object>of("id", "gpt-4o", "contextWindow", 128000)
        );
        String cachedJson = objectMapper.writeValueAsString(models);
        String cacheKey = "model_ctx:" + TENANT_ID;
        doReturn(Mono.just(cachedJson)).when(valueOps).get(cacheKey);

        StepVerifier.create(modelContextService.getModelInfo("nonexistent-model", TENANT_ID))
                .assertNext(info -> assertThat(info).isEmpty())
                .verifyComplete();
    }
}
