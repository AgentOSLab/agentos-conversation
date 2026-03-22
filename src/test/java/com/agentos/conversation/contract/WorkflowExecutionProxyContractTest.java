package com.agentos.conversation.contract;

import com.agentos.conversation.api.WorkflowExecutionProxyController;
import com.agentos.conversation.security.IamPep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Contract tests for {@code /api/v1/workflow-executions/**} (Conversation → Workflow Runtime proxy).
 *
 * <p>Spec: {@code repos/agentos-spec/api/conversation/conversation-proxies.yaml}.
 *
 * <p>Uses {@link WebTestClient#bindToController} so mappings are exercised without a full
 * {@code @WebFluxTest} security slice (avoids handler-registration edge cases for this controller).
 */
@ExtendWith(MockitoExtension.class)
class WorkflowExecutionProxyContractTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID EXEC_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private static final String LIST_JSON = "{\"content\":[{\"id\":\"00000000-0000-0000-0000-000000000099\","
            + "\"tenantId\":\"00000000-0000-0000-0000-000000000001\",\"userId\":\"00000000-0000-0000-0000-000000000002\","
            + "\"workflowId\":\"wf-demo\",\"status\":\"completed\"}],\"totalElements\":1,\"page\":0,\"size\":20,\"totalPages\":1}";

    private static final String DETAIL_JSON = "{\"id\":\"00000000-0000-0000-0000-000000000099\","
            + "\"tenantId\":\"00000000-0000-0000-0000-000000000001\",\"userId\":\"00000000-0000-0000-0000-000000000002\","
            + "\"workflowId\":\"wf-demo\",\"status\":\"completed\"}";

    private static final String STEPS_JSON = "[{\"id\":\"11111111-1111-1111-1111-111111111111\","
            + "\"executionId\":\"00000000-0000-0000-0000-000000000099\",\"stepId\":\"step1\","
            + "\"stepType\":\"condition\",\"status\":\"completed\"}]";

    @Mock
    private IamPep iamPep;

    private WebTestClient webTestClient;

    private static Mono<ClientResponse> json(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }

    @BeforeEach
    void setUp() {
        when(iamPep.require(any(UUID.class), any(UUID.class), anyString(), anyString()))
                .thenReturn(Mono.empty());

        ExchangeFunction fn = request -> {
            String path = request.url().getPath();
            if ("/api/internal/v1/workflow-executions".equals(path)) {
                return json(HttpStatus.OK, LIST_JSON);
            }
            if (path.endsWith("/steps")) {
                return json(HttpStatus.OK, STEPS_JSON);
            }
            if (path.startsWith("/api/internal/v1/workflow-executions/")) {
                return json(HttpStatus.OK, DETAIL_JSON);
            }
            return json(HttpStatus.NOT_FOUND, "{}");
        };
        WebClient upstream = WebClient.builder().exchangeFunction(fn).build();
        WorkflowExecutionProxyController controller = new WorkflowExecutionProxyController(iamPep, upstream);
        webTestClient = WebTestClient.bindToController(controller).build();
    }

    @Test
    @DisplayName("GET /api/v1/workflow-executions — 200 page")
    void list_returns200() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/workflow-executions")
                        .queryParam("page", 0)
                        .queryParam("pageSize", 20)
                        .build())
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content").isArray()
                .jsonPath("$.totalElements").isEqualTo(1)
                .jsonPath("$.content[0].workflowId").isEqualTo("wf-demo");
    }

    @Test
    @DisplayName("GET /api/v1/workflow-executions/{id} — 200")
    void get_returns200() {
        webTestClient.get()
                .uri("/api/v1/workflow-executions/{id}", EXEC_ID)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(EXEC_ID.toString())
                .jsonPath("$.status").isEqualTo("completed");
    }

    @Test
    @DisplayName("GET /api/v1/workflow-executions/{id}/steps — 200 array")
    void steps_returns200() {
        webTestClient.get()
                .uri("/api/v1/workflow-executions/{id}/steps", EXEC_ID)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].stepId").isEqualTo("step1")
                .jsonPath("$[0].stepType").isEqualTo("condition");
    }
}
