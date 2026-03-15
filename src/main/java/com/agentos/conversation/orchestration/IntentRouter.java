package com.agentos.conversation.orchestration;

import com.agentos.conversation.model.entity.ConversationSessionEntity;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Multi-feature intent router.
 *
 * Route decisions:
 *   SIMPLE_CHAT  -> direct LLM Gateway (no Task, no Agent Runtime)
 *   AGENT_TASK   -> create Task -> Agent Runtime (full Agentic Loop)
 *   WORKFLOW     -> create Task -> Agent Runtime (workflow mode)
 *
 * Scoring: multi-dimensional feature extraction computes a "complexity score";
 * above threshold routes to AGENT_TASK.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRouter {

    private static final double COMPLEXITY_THRESHOLD = 0.4;

    private static final Set<String> ACTION_KEYWORDS = Set.of(
            "create", "build", "deploy", "run", "execute", "generate", "analyze",
            "review", "fix", "update", "delete", "send", "migrate", "convert",
            "calculate", "compare", "extract", "summarize", "translate",
            "创建", "构建", "部署", "运行", "执行", "生成", "分析",
            "审查", "修复", "更新", "删除", "发送", "迁移", "转换",
            "计算", "对比", "提取", "总结", "翻译"
    );

    private static final Pattern GREETING_PATTERN = Pattern.compile(
            "^(hi|hello|hey|thanks|ok|好的|你好|谢谢|嗯|哦)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern MULTI_STEP_PATTERN = Pattern.compile(
            "(then|after that|next|finally|first.*then|步骤|然后|接着|最后|首先)",
            Pattern.CASE_INSENSITIVE
    );

    public RouteDecision route(String userMessage, ConversationSessionEntity session) {
        String sessionType = session.getSessionType();

        if ("WORKFLOW_SILENT".equals(sessionType) || "SCHEDULED".equals(sessionType)) {
            return RouteDecision.builder()
                    .routeType(RouteType.AGENT_TASK)
                    .interactionMode("SILENT")
                    .complexityScore(1.0)
                    .build();
        }

        if ("WORKFLOW_CHAT".equals(sessionType)) {
            return RouteDecision.builder()
                    .routeType(RouteType.WORKFLOW)
                    .interactionMode(session.getInteractionMode())
                    .complexityScore(0.8)
                    .build();
        }

        if ("AGENT_CHAT".equals(sessionType)) {
            return RouteDecision.builder()
                    .routeType(RouteType.AGENT_TASK)
                    .interactionMode(session.getInteractionMode())
                    .complexityScore(0.7)
                    .build();
        }

        double score = computeComplexityScore(userMessage);

        if (score < COMPLEXITY_THRESHOLD) {
            return RouteDecision.builder()
                    .routeType(RouteType.SIMPLE_CHAT)
                    .interactionMode("INTERACTIVE")
                    .complexityScore(score)
                    .build();
        }

        return RouteDecision.builder()
                .routeType(RouteType.AGENT_TASK)
                .interactionMode(session.getInteractionMode())
                .complexityScore(score)
                .build();
    }

    /**
     * Compute message complexity score (0.0 ~ 1.0).
     * Weighted sum of features:
     *   - Message length (short -> simple)
     *   - Action keywords (action verbs -> agent)
     *   - Multi-step indicators (sequence words -> agent/workflow)
     *   - Question patterns (pure question -> simple)
     *   - Greeting patterns (greeting -> simple)
     */
    double computeComplexityScore(String message) {
        if (message == null || message.isBlank()) return 0.0;

        double score = 0.0;
        String lower = message.toLowerCase().trim();

        if (GREETING_PATTERN.matcher(lower).find() && message.length() < 30) {
            return 0.05;
        }

        if (message.length() > 200) score += 0.3;
        else if (message.length() > 100) score += 0.2;
        else if (message.length() > 50) score += 0.1;

        long actionCount = ACTION_KEYWORDS.stream()
                .filter(kw -> lower.contains(kw))
                .count();
        score += Math.min(actionCount * 0.15, 0.4);

        if (MULTI_STEP_PATTERN.matcher(lower).find()) {
            score += 0.2;
        }

        if (lower.endsWith("?") || lower.endsWith("？")) {
            if (actionCount == 0) score -= 0.15;
        }

        return Math.max(0.0, Math.min(1.0, score));
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
        @Builder.Default
        private double complexityScore = 0.0;
    }
}
