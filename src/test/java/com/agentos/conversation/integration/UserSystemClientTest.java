package com.agentos.conversation.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserSystemClientTest {

    private ExchangeFunction exchangeFunction;
    private UserSystemClient client;

    @BeforeEach
    void setUp() {
        exchangeFunction = mock(ExchangeFunction.class);
        WebClient webClient = WebClient.builder().exchangeFunction(exchangeFunction).build();
        client = new UserSystemClient(webClient);
    }

    @Test
    void getUserPermissions_success() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"roles\":[\"admin\"],\"permissions\":[\"read\",\"write\"]}")
                .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        StepVerifier.create(client.getUserPermissions(userId, tenantId))
                .assertNext(map -> {
                    assertThat(map).containsKey("roles");
                    assertThat(map).containsKey("permissions");
                })
                .verifyComplete();
    }

    @Test
    void getUserPermissions_error_returnsEmptyMap() {
        when(exchangeFunction.exchange(any())).thenReturn(Mono.error(new RuntimeException("User System unavailable")));

        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        StepVerifier.create(client.getUserPermissions(userId, tenantId))
                .assertNext(map -> assertThat(map).isEmpty())
                .verifyComplete();
    }

    @Test
    void putUserCredential_success() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"scope\":\"api\",\"key\":\"key1\"}")
                .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String scope = "api";
        String key = "key1";
        Map<String, Object> body = Map.of("value", "secret");

        StepVerifier.create(client.putUserCredential(tenantId, userId, scope, key, body))
                .assertNext(map -> {
                    assertThat(map).containsEntry("scope", "api");
                    assertThat(map).containsEntry("key", "key1");
                })
                .verifyComplete();
    }

    @Test
    void listUserCredentials_success() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("[{\"scope\":\"api\",\"key\":\"key1\"},{\"scope\":\"db\",\"key\":\"key2\"}]")
                .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        StepVerifier.create(client.listUserCredentials(tenantId, userId))
                .assertNext(list -> {
                    assertThat(list).hasSize(2);
                    assertThat(list.get(0)).containsEntry("scope", "api");
                    assertThat(list.get(1)).containsEntry("scope", "db");
                })
                .verifyComplete();
    }

    @Test
    void deleteUserCredential_success() {
        ClientResponse response = ClientResponse.create(HttpStatus.NO_CONTENT).build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));

        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String scope = "api";
        String key = "key1";

        StepVerifier.create(client.deleteUserCredential(tenantId, userId, scope, key))
                .verifyComplete();
    }
}
