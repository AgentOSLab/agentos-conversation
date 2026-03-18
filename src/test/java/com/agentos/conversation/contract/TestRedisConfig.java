package com.agentos.conversation.contract;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Provides a no-op Redis stub for contract tests.
 * All Redis operations are silently dropped so tests remain self-contained.
 */
@TestConfiguration
public class TestRedisConfig {

    @Bean
    public ReactiveRedisConnectionFactory reactiveRedisConnectionFactory() {
        return mock(ReactiveRedisConnectionFactory.class);
    }

    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(
            ReactiveRedisConnectionFactory factory) {
        return mock(ReactiveStringRedisTemplate.class);
    }
}
