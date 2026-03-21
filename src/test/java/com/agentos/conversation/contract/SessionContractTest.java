package com.agentos.conversation.contract;

import com.agentos.conversation.api.ConversationSessionController;
import com.agentos.conversation.config.SecurityConfig;
import com.agentos.conversation.security.IamPep;
import com.agentos.conversation.model.dto.*;
import com.agentos.conversation.model.entity.ConversationSessionEntity;
import com.agentos.conversation.orchestration.MessageOrchestrator;
import com.agentos.conversation.service.ConversationSessionService;
import com.agentos.common.context.TenantContext;
import com.agentos.common.model.PageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.http.codec.ServerSentEvent;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Contract tests for /api/v1/sessions endpoints.
 *
 * Validates that the ConversationSessionController conforms to the API contract
 * defined in repos/agentos-spec/api/conversation/conversation.yaml.
 *
 * Uses @WebFluxTest slice with mocked service to keep tests fast and isolated.
 * Covers: create, get, list, updateTitle, complete, archive, getMessages.
 */
@WebFluxTest(controllers = ConversationSessionController.class)
@Import(SecurityConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionContractTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID   = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Autowired private WebTestClient webTestClient;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private ConversationSessionService sessionService;
    @MockBean private MessageOrchestrator messageOrchestrator;
    @MockBean private IamPep iamPep;

    @BeforeEach
    void stubIamPep() {
        when(iamPep.require(any(UUID.class), any(UUID.class), anyString(), anyString()))
                .thenReturn(Mono.empty());
    }

    /** Simulates API Gateway trusted headers so {@link com.agentos.common.reactive.ReactiveTenantContextFilter} can populate security context. */
    private void gatewayTrustHeaders(HttpHeaders h) {
        h.set(TenantContext.AUTHENTICATED_TENANT_HEADER, TENANT_ID.toString());
        h.set(TenantContext.AUTHENTICATED_USER_HEADER, USER_ID.toString());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private ConversationSessionEntity sessionEntity(String sessionType, String status) {
        ConversationSessionEntity e = new ConversationSessionEntity();
        e.setId(SESSION_ID);
        e.setTenantId(TENANT_ID);
        e.setUserId(USER_ID);
        e.setSessionType(sessionType);
        e.setInteractionMode("INTERACTIVE");
        e.setStatus(status);
        e.setTitle("Test Session");
        e.setMessageCount(0);
        e.setTaskCount(0);
        e.setTotalTokens(0L);
        e.setCreatedAt(OffsetDateTime.now());
        e.setUpdatedAt(OffsetDateTime.now());
        e.setLastActivityAt(OffsetDateTime.now());
        return e;
    }

    // ── POST /api/v1/sessions ──────────────────────────────────────────────────

    @Test @Order(1)
    @DisplayName("POST /api/v1/sessions — 201 Created with valid request")
    void createSession_returns201() throws Exception {
        ConversationSessionEntity created = sessionEntity("AGENT_CHAT", "active");
        when(sessionService.createSession(eq(TENANT_ID), eq(USER_ID), any(CreateSessionRequest.class)))
                .thenReturn(Mono.just(created));

        Map<String, Object> body = Map.of(
                "sessionType", "AGENT_CHAT",
                "interactionMode", "INTERACTIVE",
                "title", "Test Session"
        );

        webTestClient.post().uri("/api/v1/sessions")
                .headers(this::gatewayTrustHeaders)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(body))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isEqualTo(SESSION_ID.toString())
                .jsonPath("$.sessionType").isEqualTo("AGENT_CHAT")
                .jsonPath("$.status").isEqualTo("active")
                .jsonPath("$.interactionMode").isEqualTo("INTERACTIVE");
    }

    @Test @Order(2)
    @DisplayName("POST /api/v1/sessions — 400 Bad Request when sessionType missing")
    void createSession_missingSessionType_returns400() throws Exception {
        Map<String, Object> body = Map.of("title", "No type");

        webTestClient.post().uri("/api/v1/sessions")
                .headers(this::gatewayTrustHeaders)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(body))
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ── GET /api/v1/sessions/{sessionId} ──────────────────────────────────────

    @Test @Order(3)
    @DisplayName("GET /api/v1/sessions/{id} — 200 OK for existing session")
    void getSession_returns200() {
        when(sessionService.getSessionForUser(TENANT_ID, SESSION_ID, USER_ID))
                .thenReturn(Mono.just(sessionEntity("AGENT_CHAT", "active")));

        webTestClient.get().uri("/api/v1/sessions/" + SESSION_ID)
                .headers(this::gatewayTrustHeaders)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(SESSION_ID.toString())
                .jsonPath("$.tenantId").isEqualTo(TENANT_ID.toString());
    }

    @Test @Order(4)
    @DisplayName("GET /api/v1/sessions/{id} — 404 Not Found for missing session")
    void getSession_notFound_returns404() {
        when(sessionService.getSessionForUser(eq(TENANT_ID), any(UUID.class), eq(USER_ID)))
                .thenReturn(Mono.empty());

        webTestClient.get().uri("/api/v1/sessions/00000000-0000-0000-0000-999999999999")
                .headers(this::gatewayTrustHeaders)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── GET /api/v1/sessions ───────────────────────────────────────────────────

    @Test @Order(5)
    @DisplayName("GET /api/v1/sessions — 200 OK with paged results")
    void listSessions_returns200() {
        PageResponse<ConversationSessionEntity> page = PageResponse.of(
                Collections.singletonList(sessionEntity("AGENT_CHAT", "active")),
                null, 1L);
        when(sessionService.listSessions(eq(TENANT_ID), eq(USER_ID), isNull(), isNull(), eq(20), eq(0L)))
                .thenReturn(Mono.just(page));

        webTestClient.get().uri("/api/v1/sessions")
                .headers(this::gatewayTrustHeaders)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items").isArray()
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.totalCount").isEqualTo(1);
    }

    @Test @Order(6)
    @DisplayName("GET /api/v1/sessions — 200 OK filtered by status=active")
    void listSessions_filteredByStatus_returns200() {
        PageResponse<ConversationSessionEntity> page = PageResponse.of(
                Collections.singletonList(sessionEntity("AGENT_CHAT", "active")),
                null, 1L);
        when(sessionService.listSessions(eq(TENANT_ID), eq(USER_ID), eq("active"), isNull(), eq(20), eq(0L)))
                .thenReturn(Mono.just(page));

        webTestClient.get().uri("/api/v1/sessions?status=active")
                .headers(this::gatewayTrustHeaders)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].status").isEqualTo("active");
    }

    // ── PATCH /api/v1/sessions/{id}/title ─────────────────────────────────────

    @Test @Order(7)
    @DisplayName("PATCH /api/v1/sessions/{id}/title — 200 OK updates title")
    void updateTitle_returns200() throws Exception {
        ConversationSessionEntity updated = sessionEntity("AGENT_CHAT", "active");
        updated.setTitle("New Title");
        when(sessionService.updateTitle(TENANT_ID, SESSION_ID, USER_ID, "New Title"))
                .thenReturn(Mono.just(updated));

        webTestClient.patch().uri("/api/v1/sessions/" + SESSION_ID + "/title")
                .headers(this::gatewayTrustHeaders)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(Map.of("title", "New Title")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.title").isEqualTo("New Title");
    }

    // ── POST /api/v1/sessions/{id}/complete ───────────────────────────────────

    @Test @Order(8)
    @DisplayName("POST /api/v1/sessions/{id}/complete — 200 OK transitions to completed")
    void completeSession_returns200() {
        ConversationSessionEntity completed = sessionEntity("AGENT_CHAT", "completed");
        when(sessionService.updateSessionStatus(TENANT_ID, SESSION_ID, USER_ID, "completed"))
                .thenReturn(Mono.just(completed));

        webTestClient.post().uri("/api/v1/sessions/" + SESSION_ID + "/complete")
                .headers(this::gatewayTrustHeaders)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("completed");
    }

    // ── POST /api/v1/sessions/{id}/archive ────────────────────────────────────

    @Test @Order(9)
    @DisplayName("POST /api/v1/sessions/{id}/archive — 200 OK transitions to archived")
    void archiveSession_returns200() {
        ConversationSessionEntity archived = sessionEntity("AGENT_CHAT", "archived");
        when(sessionService.updateSessionStatus(TENANT_ID, SESSION_ID, USER_ID, "archived"))
                .thenReturn(Mono.just(archived));

        webTestClient.post().uri("/api/v1/sessions/" + SESSION_ID + "/archive")
                .headers(this::gatewayTrustHeaders)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("archived");
    }

    // ── GET /api/v1/sessions/{id}/messages ────────────────────────────────────

    @Test @Order(10)
    @DisplayName("GET /api/v1/sessions/{id}/messages — 200 OK returns paged messages")
    void getMessages_returns200() {
        PageResponse<com.agentos.conversation.model.entity.ConversationMessageEntity> emptyPage =
                PageResponse.of(Collections.emptyList(), null, 0L);
        when(sessionService.getSessionForUser(TENANT_ID, SESSION_ID, USER_ID))
                .thenReturn(Mono.just(sessionEntity("AGENT_CHAT", "active")));
        when(sessionService.getMessagesPaged(SESSION_ID, 50, 0L))
                .thenReturn(Mono.just(emptyPage));

        webTestClient.get().uri("/api/v1/sessions/" + SESSION_ID + "/messages")
                .headers(this::gatewayTrustHeaders)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items").isArray()
                .jsonPath("$.totalCount").isEqualTo(0);
    }

    // ── PATCH /api/v1/sessions/{id}/mcp-tools ─────────────────────────────────

    @Test @Order(11)
    @DisplayName("PATCH /api/v1/sessions/{id}/mcp-tools — 200 OK updates tool config")
    void updateMcpToolConfig_returns200() throws Exception {
        ConversationSessionEntity updated = sessionEntity("AGENT_CHAT", "active");
        when(sessionService.updateMcpToolConfig(eq(TENANT_ID), eq(SESSION_ID), eq(USER_ID), any(Map.class)))
                .thenReturn(Mono.just(updated));

        Map<String, Object> config = Map.of("enabledSkillPackageIds", Collections.emptyList());

        webTestClient.patch().uri("/api/v1/sessions/" + SESSION_ID + "/mcp-tools")
                .headers(this::gatewayTrustHeaders)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(config))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(SESSION_ID.toString());
    }

    // ── POST /api/v1/sessions/{id}/messages (sendMessage) ─────────────────────

    @Test @Order(12)
    @DisplayName("POST /api/v1/sessions/{id}/messages — 202 Accepted returns RunResponse")
    void sendMessage_returns202() throws Exception {
        com.agentos.conversation.model.dto.RunResponse runResponse =
                com.agentos.conversation.model.dto.RunResponse.builder()
                        .runId(UUID.fromString("00000000-0000-0000-0000-000000000099"))
                        .sessionId(SESSION_ID)
                        .status("routing")
                        .build();

        when(messageOrchestrator.createAndExecuteRun(eq(TENANT_ID), eq(USER_ID), eq(SESSION_ID), any()))
                .thenReturn(Mono.just(runResponse));

        Map<String, Object> body = Map.of("content", "Hello, what is the status of JIRA-123?");

        webTestClient.post().uri("/api/v1/sessions/" + SESSION_ID + "/messages")
                .headers(this::gatewayTrustHeaders)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(body))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.runId").isEqualTo("00000000-0000-0000-0000-000000000099")
                .jsonPath("$.sessionId").isEqualTo(SESSION_ID.toString())
                .jsonPath("$.status").isEqualTo("routing");
    }

    @Test @Order(13)
    @DisplayName("POST /api/v1/sessions/{id}/messages — 400 Bad Request when content is blank")
    void sendMessage_blankContent_returns400() throws Exception {
        Map<String, Object> body = Map.of("content", "");

        webTestClient.post().uri("/api/v1/sessions/" + SESSION_ID + "/messages")
                .headers(this::gatewayTrustHeaders)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(body))
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ── GET /api/v1/sessions/{id}/events (session SSE stream) ─────────────────

    @Test @Order(14)
    @DisplayName("GET /api/v1/sessions/{id}/events — 200 OK returns SSE stream")
    void streamSessionEvents_returns200() {
        ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                .id("evt-1")
                .event("step.completed")
                .data("{\"stepId\":\"step1\"}")
                .build();

        when(sessionService.getSessionForUser(TENANT_ID, SESSION_ID, USER_ID))
                .thenReturn(Mono.just(sessionEntity("AGENT_CHAT", "active")));
        when(messageOrchestrator.streamSessionEvents(SESSION_ID))
                .thenReturn(Flux.just(event));

        webTestClient.get()
                .uri("/api/v1/sessions/" + SESSION_ID + "/events")
                .headers(this::gatewayTrustHeaders)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
    }

    @Test @Order(15)
    @DisplayName("GET /api/v1/sessions/{id}/events — 200 OK with empty stream when no events pending")
    void streamSessionEvents_emptyStream_returns200() {
        when(sessionService.getSessionForUser(TENANT_ID, SESSION_ID, USER_ID))
                .thenReturn(Mono.just(sessionEntity("AGENT_CHAT", "active")));
        when(messageOrchestrator.streamSessionEvents(SESSION_ID))
                .thenReturn(Flux.empty());

        webTestClient.get()
                .uri("/api/v1/sessions/" + SESSION_ID + "/events")
                .headers(this::gatewayTrustHeaders)
                .header("X-Tenant-Id", TENANT_ID.toString())
                .header("X-User-Id", USER_ID.toString())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk();
    }
}
