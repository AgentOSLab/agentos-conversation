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

class AgentRuntimeClientTest {

    private ExchangeFunction exchangeFunction;
    private AgentRuntimeClient client;

    @BeforeEach
    void setUp() {
        exchangeFunction = mock(ExchangeFunction.class);
        WebClient webClient = WebClient.builder().exchangeFunction(exchangeFunction).build();
        client = new AgentRuntimeClient(webClient);
    }

    @Test
    void submitTask_success() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"id\":\"task-1\",\"status\":\"PENDING\"}")
                .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        Map<String, Object> taskRequest = Map.of("agentId", "agent-1", "input", "hello");
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        StepVerifier.create(client.submitTask(taskRequest, tenantId, userId))
                .assertNext(map -> {
                    assertThat(map).containsEntry("id", "task-1");
                    assertThat(map).containsEntry("status", "PENDING");
                })
                .verifyComplete();
    }

    @Test
    void getTask_success() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"id\":\"task-1\",\"status\":\"RUNNING\"}")
                .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        UUID taskId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        StepVerifier.create(client.getTask(taskId, tenantId))
                .assertNext(map -> {
                    assertThat(map).containsEntry("id", "task-1");
                    assertThat(map).containsEntry("status", "RUNNING");
                })
                .verifyComplete();
    }

    @Test
    void submitHumanInput_success() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"status\":\"ACCEPTED\"}")
                .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        UUID taskId = UUID.randomUUID();
        Map<String, Object> input = Map.of("approved", true);
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        StepVerifier.create(client.submitHumanInput(taskId, input, tenantId, userId))
                .assertNext(map -> assertThat(map).containsEntry("status", "ACCEPTED"))
                .verifyComplete();
    }

    @Test
    void cancelTask_success() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        UUID taskId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        StepVerifier.create(client.cancelTask(taskId, tenantId))
                .verifyComplete();
    }

    @Test
    void checkReadiness_success() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"ready\":true,\"issues\":[]}")
                .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        UUID agentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        StepVerifier.create(client.checkReadiness(agentId, userId, tenantId))
                .assertNext(map -> {
                    assertThat(map).containsEntry("ready", true);
                    assertThat((List<?>) map.get("issues")).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void checkReadiness_error_failsOpen() {
        when(exchangeFunction.exchange(any())).thenReturn(Mono.error(new RuntimeException("Agent Runtime unavailable")));

        UUID agentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        StepVerifier.create(client.checkReadiness(agentId, userId, tenantId))
                .assertNext(map -> {
                    assertThat(map).containsEntry("ready", true);
                    assertThat((List<?>) map.get("issues")).isEmpty();
                })
                .verifyComplete();
    }
}
