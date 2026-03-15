package com.agentos.conversation.orchestration;

import com.agentos.conversation.model.entity.ConversationSessionEntity;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Classifies user intent and determines the execution path.
 * Routing decisions:
 *   SIMPLE_CHAT    → direct LLM Gateway call (no Task, no Agent Runtime)
 *   TOOL_CALL      → create lightweight Task or direct MCP call
 *   AGENT_TASK     → create Task → Agent Runtime (full agentic loop)
 *   WORKFLOW        → create Task → Agent Runtime (workflow mode)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRouter {

    public RouteDecision route(String userMessage, ConversationSessionEntity session) {
        String sessionType = session.getSessionType();

        if ("WORKFLOW_SILENT".equals(sessionType) || "SCHEDULED".equals(sessionType)) {
            return RouteDecision.builder()
                    .routeType(RouteType.AGENT_TASK)
                    .interactionMode("SILENT")
                    .build();
        }

        if ("WORKFLOW_CHAT".equals(sessionType)) {
            return RouteDecision.builder()
                    .routeType(RouteType.WORKFLOW)
                    .interactionMode(session.getInteractionMode())
                    .build();
        }

        if ("AGENT_CHAT".equals(sessionType)) {
            return RouteDecision.builder()
                    .routeType(RouteType.AGENT_TASK)
                    .interactionMode(session.getInteractionMode())
                    .build();
        }

        if (isSimpleChat(userMessage)) {
            return RouteDecision.builder()
                    .routeType(RouteType.SIMPLE_CHAT)
                    .interactionMode("INTERACTIVE")
                    .build();
        }

        return RouteDecision.builder()
                .routeType(RouteType.AGENT_TASK)
                .interactionMode(session.getInteractionMode())
                .build();
    }

    private boolean isSimpleChat(String message) {
        if (message == null || message.isBlank()) return true;
        if (message.length() < 50) {
            String lower = message.toLowerCase().trim();
            return lower.startsWith("hi") || lower.startsWith("hello") || lower.startsWith("hey")
                    || lower.startsWith("thanks") || lower.startsWith("ok")
                    || lower.endsWith("?") && !lower.contains("create") && !lower.contains("run")
                    && !lower.contains("execute") && !lower.contains("deploy");
        }
        return false;
    }

    public enum RouteType {
        SIMPLE_CHAT,
        TOOL_CALL,
        AGENT_TASK,
        WORKFLOW
    }

    @Data
    @Builder
    public static class RouteDecision {
        private RouteType routeType;
        private String interactionMode;
    }
}
