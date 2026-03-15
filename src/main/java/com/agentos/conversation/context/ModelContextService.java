package com.agentos.conversation.context;

import com.agentos.conversation.integration.LlmGatewayClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Caches model metadata from LLM Gateway and provides context window lookups.
 * Uses Redis as L2 cache so all instances share one cached copy per tenant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelContextService {

    private static final String CACHE_KEY_PREFIX = "model_ctx:";

    private final LlmGatewayClient llmGatewayClient;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${agentos.context.model-cache-ttl-seconds:300}")
    private int cacheTtlSeconds;

    @Value("${agentos.context.default-context-window:128000}")
    private int defaultContextWindow;

    /**
     * Returns the effective context window for the tenant's primary model.
     * Picks the largest contextWindow among available models (conservative for
     * summarization — we use the model the tenant is most likely to use for
     * long conversations).
     */
    public Mono<Integer> getContextWindow(UUID tenantId) {
        return getModels(tenantId)
                .map(models -> models.stream()
                        .map(m -> {
                            Object cw = m.get("contextWindow");
                            if (cw instanceof Number n) return n.intValue();
                            return 0;
                        })
                        .max(Integer::compareTo)
                        .orElse(defaultContextWindow))
                .defaultIfEmpty(defaultContextWindow);
    }

    /**
     * Returns model info for a specific model ID, or empty if not found.
     */
    public Mono<Map<String, Object>> getModelInfo(String modelId, UUID tenantId) {
        return getModels(tenantId)
                .map(models -> models.stream()
                        .filter(m -> modelId.equals(m.get("id")))
                        .findFirst()
                        .orElse(Map.of()));
    }

    private Mono<List<Map<String, Object>>> getModels(UUID tenantId) {
        String key = CACHE_KEY_PREFIX + tenantId;

        return redisTemplate.opsForValue().get(key)
                .flatMap(json -> {
                    try {
                        List<Map<String, Object>> cached = objectMapper.readValue(
                                json, new TypeReference<>() {});
                        return Mono.just(cached);
                    } catch (Exception e) {
                        log.debug("Model cache deserialization miss for tenant {}", tenantId);
                        return Mono.<List<Map<String, Object>>>empty();
                    }
                })
                .switchIfEmpty(
                        llmGatewayClient.listModels(tenantId)
                                .flatMap(models -> {
                                    if (models.isEmpty()) return Mono.just(models);
                                    try {
                                        String json = objectMapper.writeValueAsString(models);
                                        return redisTemplate.opsForValue()
                                                .set(key, json, Duration.ofSeconds(cacheTtlSeconds))
                                                .thenReturn(models);
                                    } catch (Exception e) {
                                        log.warn("Failed to cache model info: {}", e.getMessage());
                                        return Mono.just(models);
                                    }
                                })
                );
    }
}
