package com.agentos.conversation.orchestration;

import com.agentos.conversation.model.entity.ConversationSessionEntity;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Multi-feature intent router.
 *
 * Route decisions:
 *   SIMPLE_CHAT  -> direct LLM Gateway (no Task, no Agent Runtime)
 *   AGENT_TASK   -> create Task -> Agent Runtime (full Agentic Loop)
 *   WORKFLOW     -> create Task -> Agent Runtime (workflow mode)
 *
 * Routing pipeline (GAP-RT-001 fix):
 *   1. Session type overrides (WORKFLOW_SILENT, WORKFLOW_CHAT, AGENT_CHAT) — always deterministic
 *   2. LLM pre-classification via IntentClassifierService (fast model, 2s timeout)
 *   3. Lexical scoring fallback — when LLM classification is unavailable or times out
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRouter {

    private final IntentClassifierService intentClassifierService;

    private static final double COMPLEXITY_THRESHOLD = 0.4;

    private static final Set<String> ACTION_KEYWORDS = Set.of(
            // Core action verbs (EN)
            "create", "build", "deploy", "run", "execute", "generate", "analyze",
            "review", "fix", "update", "delete", "send", "migrate", "convert",
            "calculate", "compare", "extract", "summarize", "translate",
            // Extended action verbs (EN)
            "search", "find", "fetch", "read", "write", "save", "load", "export",
            "import", "upload", "download", "schedule", "book", "reserve",
            "process", "format", "validate", "test", "debug", "optimize",
            "refactor", "publish", "list", "query", "filter", "merge", "split",
            "rename", "move", "copy", "parse", "transform", "monitor", "alert",
            "notify", "remind", "configure", "setup", "install", "enable", "disable",
            // Core action verbs (ZH)
            "创建", "构建", "部署", "运行", "执行", "生成", "分析",
            "审查", "修复", "更新", "删除", "发送", "迁移", "转换",
            "计算", "对比", "提取", "总结", "翻译",
            // Extended action verbs (ZH)
            "搜索", "查找", "获取", "读取", "写入", "保存", "导出", "导入",
            "上传", "下载", "安排", "预订", "处理", "格式化", "验证",
            "调试", "优化", "发布", "查询", "过滤", "合并", "拆分",
            "配置", "安装", "启用", "禁用", "监控", "提醒", "通知"
    );

    /**
     * Tool-target nouns: when combined with an action verb these raise the score,
     * indicating a tool-use request (e.g. "send email", "read file", "query database").
     */
    private static final Set<String> TOOL_NOUNS = Set.of(
            // Data / file
            "file", "email", "calendar", "database", "db", "api", "code",
            "report", "document", "doc", "spreadsheet", "sheet", "table",
            "image", "photo", "video", "audio", "pdf", "csv", "json", "xml",
            // Integration surfaces
            "slack", "jira", "github", "git", "repo", "repository",
            "url", "link", "website", "page", "webhook", "server", "service",
            // Work items
            "message", "chat", "ticket", "issue", "task", "event", "meeting",
            // ZH equivalents
            "文件", "邮件", "日历", "数据库", "代码", "报告", "文档",
            "表格", "图片", "视频", "链接", "消息", "工单", "任务", "会议"
    );

    private static final Pattern GREETING_PATTERN = Pattern.compile(
            "^(hi|hello|hey|thanks|ok|好的|你好|谢谢|嗯|哦)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern MULTI_STEP_PATTERN = Pattern.compile(
            "(then|after that|next|finally|first.*then|步骤|然后|接着|最后|首先)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Route a user message to the appropriate execution path.
     * Returns a Mono to allow async LLM pre-classification (GAP-RT-001 fix).
     *
     * @param userMessage the raw user message content
     * @param session     current conversation session
     * @param tenantId    tenant scope for LLM pre-classifier
     */
    public Mono<RouteDecision> route(String userMessage, ConversationSessionEntity session, UUID tenantId) {
        String sessionType = session.getSessionType();

        // Session-type overrides are deterministic — no LLM needed
        if ("WORKFLOW_SILENT".equals(sessionType) || "SCHEDULED".equals(sessionType)) {
            log.info("Intent routing decision: intent=AGENT_TASK reason=session_type_override sessionId={}", session.getId());
            return Mono.just(RouteDecision.builder()
                    .routeType(RouteType.AGENT_TASK)
                    .interactionMode("SILENT")
                    .complexityScore(1.0)
                    .build());
        }

        if ("WORKFLOW_CHAT".equals(sessionType)) {
            log.info("Intent routing decision: intent=WORKFLOW reason=session_type_override sessionId={}", session.getId());
            return Mono.just(RouteDecision.builder()
                    .routeType(RouteType.WORKFLOW)
                    .interactionMode(session.getInteractionMode())
                    .complexityScore(0.8)
                    .build());
        }

        if ("AGENT_CHAT".equals(sessionType)) {
            log.info("Intent routing decision: intent=AGENT_TASK reason=session_type_override sessionId={}", session.getId());
            return Mono.just(RouteDecision.builder()
                    .routeType(RouteType.AGENT_TASK)
                    .interactionMode(session.getInteractionMode())
                    .complexityScore(0.7)
                    .build());
        }

        // Explicit Hub binding: when the session is tied to an agent, never route to SIMPLE_CHAT.
        // (User selected an agent — execution must go through Agent Runtime, not direct LLM.)
        if (session.getBoundEntityId() != null && isAgentBinding(session)) {
            log.info("Intent routing decision: intent=AGENT_TASK reason=bound_agent sessionId={} boundEntityId={}",
                    session.getId(), session.getBoundEntityId());
            return Mono.just(RouteDecision.builder()
                    .routeType(RouteType.AGENT_TASK)
                    .interactionMode(session.getInteractionMode() != null ? session.getInteractionMode() : "INTERACTIVE")
                    .complexityScore(0.85)
                    .build());
        }

        // GAP-RT-001 fix: attempt LLM pre-classification, fall back to lexical scoring on failure
        return intentClassifierService.classify(userMessage, tenantId)
                .map(llmResult -> {
                    if (llmResult.isPresent()) {
                        RouteType routeType = llmResult.get();
                        log.info("Intent routing decision: intent={} reason=llm_classifier sessionId={}", routeType, session.getId());
                        return RouteDecision.builder()
                                .routeType(routeType)
                                .interactionMode(routeType == RouteType.SIMPLE_CHAT
                                        ? "INTERACTIVE" : session.getInteractionMode())
                                .complexityScore(routeType == RouteType.AGENT_TASK ? 0.8 : 0.2)
                                .build();
                    }
                    RouteDecision decision = lexicalRoute(userMessage, session);
                    log.info("Intent routing decision: intent={} reason=lexical_fallback sessionId={}", decision.getRouteType(), session.getId());
                    return decision;
                });
    }

    /** Lexical scoring fallback — original scoring logic preserved unchanged. */
    private RouteDecision lexicalRoute(String userMessage, ConversationSessionEntity session) {
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
     *   - Tool-noun keywords (tool targets -> raise score when combined with actions)
     *   - Multi-step indicators (sequence words -> agent/workflow)
     *   - Question patterns (pure question -> simple)
     *   - Greeting patterns (greeting -> simple)
     */
    /**
     * Agent-bound session: {@code boundEntityType} is absent/blank (legacy) or explicitly {@code agent}.
     * Other bound types (e.g. workflow) are not forced here — they rely on session type or classifier.
     */
    private static boolean isAgentBinding(ConversationSessionEntity session) {
        String t = session.getBoundEntityType();
        return t == null || t.isBlank() || "agent".equalsIgnoreCase(t);
    }

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

        // Verb+noun tool-use signal: action verb AND a tool noun together → clear tool call intent.
        // Even a single pairing (e.g. "send email", "read file") is strong evidence of an agent task.
        if (actionCount > 0) {
            long toolNounCount = TOOL_NOUNS.stream()
                    .filter(noun -> lower.contains(noun))
                    .count();
            if (toolNounCount > 0) {
                score += Math.min(toolNounCount * 0.1, 0.2);
            }
        }

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
