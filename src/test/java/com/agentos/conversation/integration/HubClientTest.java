package com.agentos.conversation.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HubClientTest {

    private ExchangeFunction exchangeFunction;
    private HubClient client;

    @BeforeEach
    void setUp() {
        exchangeFunction = mock(ExchangeFunction.class);
        WebClient webClient = WebClient.builder().exchangeFunction(exchangeFunction).build();
        client = new HubClient(webClient);
    }

    @Test
    void getAgent_success() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"id\":\"agent-1\",\"name\":\"Test Agent\"}")
                .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        UUID agentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        StepVerifier.create(client.getAgent(agentId, tenantId))
                .assertNext(map -> {
                    assertThat(map).containsEntry("id", "agent-1");
                    assertThat(map).containsEntry("name", "Test Agent");
                })
                .verifyComplete();
    }

    @Test
    void getSkillPackage_success() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"id\":\"pkg-1\",\"name\":\"My Skill Package\"}")
                .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        UUID skillPackageId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        StepVerifier.create(client.getSkillPackage(skillPackageId, tenantId))
                .assertNext(map -> {
                    assertThat(map).containsEntry("id", "pkg-1");
                    assertThat(map).containsEntry("name", "My Skill Package");
                })
                .verifyComplete();
    }
}
