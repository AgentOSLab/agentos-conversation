package com.agentos.conversation.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmGatewayClientTest {

    private ExchangeFunction exchangeFunction;
    private LlmGatewayClient client;

    @BeforeEach
    void setUp() {
        exchangeFunction = mock(ExchangeFunction.class);
        WebClient webClient = WebClient.builder().exchangeFunction(exchangeFunction).build();
        client = new LlmGatewayClient(webClient);
    }

    @Test
    void chatCompletion_success() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"choices\":[{\"message\":{\"content\":\"Hi\"}}]}")
                .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "Hello"));
        UUID tenantId = UUID.randomUUID();

        StepVerifier.create(client.chatCompletion(messages, null, tenantId))
                .assertNext(map -> {
                    assertThat(map).containsKey("choices");
                    assertThat((List<?>) map.get("choices")).hasSize(1);
                })
                .verifyComplete();
    }

    @Test
    void chatCompletion_withTools() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"choices\":[]}")
                .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "Hi"));
        List<Map<String, Object>> tools = List.of(Map.of("type", "function", "function", Map.of("name", "test")));
        UUID tenantId = UUID.randomUUID();

        StepVerifier.create(client.chatCompletion(messages, tools, tenantId))
                .assertNext(map -> assertThat(map).containsKey("choices"))
                .verifyComplete();
    }

    @Test
    void listModels_success() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"models\":[{\"id\":\"gpt-4\"},{\"id\":\"gpt-3.5\"}]}")
                .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        UUID tenantId = UUID.randomUUID();

        StepVerifier.create(client.listModels(tenantId))
                .assertNext(models -> {
                    assertThat(models).hasSize(2);
                    assertThat(models.get(0)).containsEntry("id", "gpt-4");
                    assertThat(models.get(1)).containsEntry("id", "gpt-3.5");
                })
                .verifyComplete();
    }

    @Test
    void listModels_error_returnsEmptyList() {
        when(exchangeFunction.exchange(any())).thenReturn(Mono.error(new RuntimeException("Gateway unavailable")));

        UUID tenantId = UUID.randomUUID();

        StepVerifier.create(client.listModels(tenantId))
                .assertNext(models -> assertThat(models).isEmpty())
                .verifyComplete();
    }

    @Test
    void chat_withTenantId() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"choices\":[]}")
                .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        Map<String, Object> requestBody = Map.of(
                "tenantId", UUID.randomUUID().toString(),
                "messages", List.of(Map.of("role", "user", "content", "Hi"))
        );

        StepVerifier.create(client.chat(requestBody))
                .assertNext(map -> assertThat(map).containsKey("choices"))
                .verifyComplete();
    }

    @Test
    void chat_withoutTenantId() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"choices\":[]}")
                .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        Map<String, Object> requestBody = Map.of("messages", List.of(Map.of("role", "user", "content", "Hi")));

        StepVerifier.create(client.chat(requestBody))
                .assertNext(map -> assertThat(map).containsKey("choices"))
                .verifyComplete();
    }
}
