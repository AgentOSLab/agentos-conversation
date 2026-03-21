package com.agentos.conversation.orchestration;

import com.agentos.conversation.model.entity.ConversationSessionEntity;
import com.agentos.conversation.orchestration.IntentRouter.RouteDecision;
import com.agentos.conversation.orchestration.IntentRouter.RouteType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for IntentRouter.
 * Aligned with conversation spec: CreateRunRequest, RunRouteType (simple_chat, agent_task, workflow).
 */
@ExtendWith(MockitoExtension.class)
class IntentRouterTest {

    @Mock
    private IntentClassifierService intentClassifierService;

    private IntentRouter router;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Session-type overrides skip the classifier; lenient avoids strict unnecessary-stub errors.
        lenient().when(intentClassifierService.classify(anyString(), any(UUID.class)))
                .thenReturn(Mono.just(Optional.empty()));
        router = new IntentRouter(intentClassifierService);
    }

    @Nested
    @DisplayName("route by session type (spec RunRouteType)")
    class RouteBySessionType {

        @Test
        @DisplayName("WORKFLOW_SILENT session -> agent_task, SILENT")
        void workflowSilent_returnsAgentTask() {
            ConversationSessionEntity session = sessionEntity("WORKFLOW_SILENT", "SILENT");
            RouteDecision d = router.route("any message", session, tenantId).block();
            assertThat(d.getRouteType()).isEqualTo(RouteType.AGENT_TASK);
            assertThat(d.getInteractionMode()).isEqualTo("SILENT");
            assertThat(d.getComplexityScore()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("SCHEDULED session -> agent_task, SILENT")
        void scheduled_returnsAgentTask() {
            ConversationSessionEntity session = sessionEntity("SCHEDULED", "SILENT");
            RouteDecision d = router.route("scheduled task", session, tenantId).block();
            assertThat(d.getRouteType()).isEqualTo(RouteType.AGENT_TASK);
            assertThat(d.getInteractionMode()).isEqualTo("SILENT");
        }

        @Test
        @DisplayName("WORKFLOW_CHAT session -> workflow")
        void workflowChat_returnsWorkflow() {
            ConversationSessionEntity session = sessionEntity("WORKFLOW_CHAT", "INTERACTIVE");
            RouteDecision d = router.route("run workflow", session, tenantId).block();
            assertThat(d.getRouteType()).isEqualTo(RouteType.WORKFLOW);
            assertThat(d.getInteractionMode()).isEqualTo("INTERACTIVE");
            assertThat(d.getComplexityScore()).isEqualTo(0.8);
        }

        @Test
        @DisplayName("AGENT_CHAT session -> agent_task")
        void agentChat_returnsAgentTask() {
            ConversationSessionEntity session = sessionEntity("AGENT_CHAT", "INTERACTIVE");
            RouteDecision d = router.route("help me with this", session, tenantId).block();
            assertThat(d.getRouteType()).isEqualTo(RouteType.AGENT_TASK);
            assertThat(d.getInteractionMode()).isEqualTo("INTERACTIVE");
            assertThat(d.getComplexityScore()).isEqualTo(0.7);
        }
    }

    @Nested
    @DisplayName("route by message complexity (default session)")
    class RouteByComplexity {

        private ConversationSessionEntity defaultSession;

        @BeforeEach
        void setUp() {
            defaultSession = sessionEntity("SIMPLE_CHAT", "INTERACTIVE");
        }

        @Test
        @DisplayName("short greeting -> simple_chat (spec RunRouteType)")
        void shortGreeting_returnsSimpleChat() {
            RouteDecision d = router.route("Hi!", defaultSession, tenantId).block();
            assertThat(d.getRouteType()).isEqualTo(RouteType.SIMPLE_CHAT);
            assertThat(d.getComplexityScore()).isLessThan(0.4);
        }

        @Test
        @DisplayName("hello with short message -> simple_chat")
        void helloShort_returnsSimpleChat() {
            RouteDecision d = router.route("Hello, how are you?", defaultSession, tenantId).block();
            assertThat(d.getRouteType()).isEqualTo(RouteType.SIMPLE_CHAT);
        }

        @Test
        @DisplayName("multiple action keywords -> agent_task")
        void actionKeyword_returnsAgentTask() {
            // Needs score >= 0.4: "create" (0.15) + "build" (0.15) + "send" (0.15) = 0.45
            RouteDecision d = router.route("Create and build a report, then send it to me", defaultSession,
                    tenantId).block();
            assertThat(d.getRouteType()).isEqualTo(RouteType.AGENT_TASK);
            assertThat(d.getComplexityScore()).isGreaterThanOrEqualTo(0.4);
        }

        @Test
        @DisplayName("multi-step 'first X then Y' -> agent_task")
        void multiStep_returnsAgentTask() {
            RouteDecision d = router.route("First analyze the data, then generate a summary", defaultSession,
                    tenantId).block();
            assertThat(d.getRouteType()).isEqualTo(RouteType.AGENT_TASK);
        }

        @Test
        @DisplayName("long message (>200 chars) with action -> agent_task")
        void longMessage_returnsAgentTask() {
            // Length >200 adds 0.3, "create" adds 0.15 -> total >= 0.4
            String longMsg = "a".repeat(220) + " create something for me";
            RouteDecision d = router.route(longMsg, defaultSession, tenantId).block();
            assertThat(d.getRouteType()).isEqualTo(RouteType.AGENT_TASK);
        }

        @Test
        @DisplayName("pure question without action -> simple_chat")
        void pureQuestion_returnsSimpleChat() {
            RouteDecision d = router.route("What is the capital of France?", defaultSession, tenantId).block();
            assertThat(d.getRouteType()).isEqualTo(RouteType.SIMPLE_CHAT);
        }

        @Test
        @DisplayName("Chinese greeting -> simple_chat")
        void chineseGreeting_returnsSimpleChat() {
            RouteDecision d = router.route("你好", defaultSession, tenantId).block();
            assertThat(d.getRouteType()).isEqualTo(RouteType.SIMPLE_CHAT);
        }

        @Test
        @DisplayName("bound agent + SIMPLE_CHAT session -> agent_task (never simple_chat)")
        void boundAgent_forcesAgentTask() {
            ConversationSessionEntity session = sessionEntity("SIMPLE_CHAT", "INTERACTIVE");
            session.setBoundEntityId(UUID.randomUUID());
            session.setBoundEntityType("agent");
            RouteDecision d = router.route("Hi!", session, tenantId).block();
            assertThat(d.getRouteType()).isEqualTo(RouteType.AGENT_TASK);
        }

        @Test
        @DisplayName("bound agent with blank entity type (legacy) -> agent_task")
        void boundAgent_blankType_forcesAgentTask() {
            ConversationSessionEntity session = sessionEntity("SIMPLE_CHAT", "INTERACTIVE");
            session.setBoundEntityId(UUID.randomUUID());
            session.setBoundEntityType(null);
            RouteDecision d = router.route("What is 2+2?", session, tenantId).block();
            assertThat(d.getRouteType()).isEqualTo(RouteType.AGENT_TASK);
        }
    }

    @Nested
    @DisplayName("computeComplexityScore edge cases")
    class ComplexityScore {

        @Test
        @DisplayName("null message returns 0")
        void nullMessage_returnsZero() {
            double score = router.computeComplexityScore(null);
            assertThat(score).isEqualTo(0.0);
        }

        @Test
        @DisplayName("blank message returns 0")
        void blankMessage_returnsZero() {
            assertThat(router.computeComplexityScore("   ")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("greeting < 30 chars returns ~0.05")
        void shortGreeting_returnsLowScore() {
            double score = router.computeComplexityScore("Hi there!");
            assertThat(score).isLessThan(0.1);
        }

        @Test
        @DisplayName("score clamped 0 to 1")
        void scoreClamped() {
            // Many action keywords could exceed 1.0
            String manyActions = "create build deploy run execute generate " +
                    "analyze review fix update delete send migrate convert";
            RouteDecision d = router.route(manyActions, sessionEntity("SIMPLE_CHAT", "INTERACTIVE"), tenantId)
                    .block();
            assertThat(d.getComplexityScore()).isBetween(0.0, 1.0);
        }
    }

    private static ConversationSessionEntity sessionEntity(String sessionType, String interactionMode) {
        ConversationSessionEntity s = new ConversationSessionEntity();
        s.setId(UUID.randomUUID());
        s.setSessionType(sessionType);
        s.setInteractionMode(interactionMode);
        return s;
    }
}
