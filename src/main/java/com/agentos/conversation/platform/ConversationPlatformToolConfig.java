package com.agentos.conversation.platform;

import com.agentos.runtime.core.platform.PlatformToolDispatcher;
import com.agentos.runtime.core.platform.PlatformToolHandler;
import com.agentos.runtime.core.platform.handler.FetchUrlToolHandler;
import com.agentos.runtime.core.platform.handler.ThinkToolHandler;
import com.agentos.runtime.core.platform.handler.WebSearchToolHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

/**
 * Wires platform tools for Conversation service's direct chat (simple_chat) path.
 *
 * <p>When a user opens a chat without selecting an agent, the Conversation service
 * handles the LLM call directly. These platform tools give the direct chat path
 * capabilities like knowledge search, web search, and reasoning — similar to how
 * ChatGPT or Claude provide tools in their default chat mode.
 *
 * <p>Available tools for direct chat:
 * <ul>
 *   <li>{@code think}            — chain-of-thought reasoning</li>
 *   <li>{@code search_knowledge} — RAG knowledge search</li>
 *   <li>{@code web_search}       — web search via configurable provider</li>
 *   <li>{@code fetch_url}        — fetch and parse web pages</li>
 *   <li>{@code save_artifact}    — save generated content</li>
 *   <li>{@code get_artifact}     — retrieve saved content</li>
 * </ul>
 *
 * <p>NOT available in direct chat (Agent-level only):
 * save_memory, recall_memory, execute_code, create_subtask, check_subtask
 */
@Slf4j
@Configuration
public class ConversationPlatformToolConfig {

    public static final Set<String> CONVERSATION_TOOLS = Set.of(
            "think", "search_knowledge", "web_search", "fetch_url", "save_artifact", "get_artifact"
    );

    @Value("${agentos.services.rag.base-url:http://localhost:8010}")
    private String ragBaseUrl;

    @Value("${agentos.platform-tools.web-search.api-url:}")
    private String webSearchApiUrl;

    @Value("${agentos.platform-tools.web-search.api-key:}")
    private String webSearchApiKey;

    @Value("${agentos.platform-tools.web-search.provider:brave}")
    private String webSearchProvider;

    @Value("${agentos.platform-tools.fetch-url.max-chars:10000}")
    private int fetchUrlMaxChars;

    /**
     * G-002 fix: default namespace pattern for Simple Chat search_knowledge.
     * When the LLM does not provide explicit namespaces, restricts search to
     * the tenant's general knowledge base instead of querying all namespaces.
     * Supports {@code {tenantId}} placeholder.
     * <p>Must align with RAG namespace access rules: owned tenant paths have the tenant UUID as the
     * <strong>second</strong> segment (e.g. {@code memory/{tenantId}/general}).
     */
    @Value("${agentos.platform-tools.search-knowledge.default-namespace:memory/{tenantId}/general}")
    private String defaultNamespacePattern;

    /**
     * Bounded wait for RAG search — handlers compose reactive calls; the orchestrator applies
     * a single bounded {@code block} per tool dispatch.
     */
    @Value("${agentos.platform-tools.timeouts.search:30s}")
    private Duration searchTimeout;

    @Value("${agentos.platform-tools.timeouts.redis:10s}")
    private Duration redisOpTimeout;

    @Bean
    public PlatformToolDispatcher conversationPlatformToolDispatcher(
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder,
            org.springframework.data.redis.core.ReactiveStringRedisTemplate redisTemplate) {

        PlatformToolDispatcher dispatcher = new PlatformToolDispatcher(objectMapper);

        dispatcher.register("think", new ThinkToolHandler(objectMapper));
        dispatcher.register("search_knowledge", searchKnowledgeHandler(webClientBuilder, objectMapper));
        dispatcher.register("web_search", new WebSearchToolHandler(objectMapper, webSearchApiUrl, webSearchApiKey, webSearchProvider));
        dispatcher.register("fetch_url", new FetchUrlToolHandler(objectMapper, fetchUrlMaxChars));
        dispatcher.register("save_artifact", saveArtifactHandler(redisTemplate, objectMapper));
        dispatcher.register("get_artifact", getArtifactHandler(redisTemplate, objectMapper));

        log.info("Conversation platform tools initialized: {}", dispatcher.registeredToolNames());
        return dispatcher;
    }

    /**
     * G-002 fix: when the LLM does not specify namespaces, defaults to the tenant's
     * general namespace (default {@code memory/{tenantId}/general}) per RAG access policy.
     */
    @SuppressWarnings("unchecked")
    private PlatformToolHandler searchKnowledgeHandler(WebClient.Builder builder, ObjectMapper om) {
        WebClient ragClient = builder.baseUrl(ragBaseUrl).build();
        return (toolName, args, ctx) -> {
            String query = (String) args.getOrDefault("query", "");
            List<String> namespaces = args.get("namespaces") instanceof List<?> ns
                    ? (List<String>) ns : List.of();
            if (namespaces.isEmpty() && ctx.getTenantId() != null) {
                String tenantDefault = defaultNamespacePattern
                        .replace("{tenantId}", ctx.getTenantId().toString());
                namespaces = List.of(tenantDefault);
                log.debug("G-002: No namespaces provided, defaulting to {}", tenantDefault);
            }
            // P7-RAG fix: cap topK to prevent LLM from requesting excessive results
            int topK = Math.min(toInt(args.get("top_k"), 5), 20);
            return ragClient.post()
                    .uri("/api/v1/search")
                    .header("X-Tenant-Id", ctx.getTenantId() != null ? ctx.getTenantId().toString() : "")
                    .bodyValue(Map.of("query", query, "namespacePaths", namespaces, "topK", topK))
                    .retrieve()
                    .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(searchTimeout)
                    .onErrorResume(e -> Mono.just(Map.of(
                            "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                            "results", List.of())))
                    .map(result -> toJson(om, result != null ? result : Map.of("results", List.of())))
                    .onErrorResume(e -> Mono.just(toJson(om,
                            Map.of("error", "Knowledge search failed: " + e.getMessage(), "results", List.of()))));
        };
    }

    private PlatformToolHandler saveArtifactHandler(
            org.springframework.data.redis.core.ReactiveStringRedisTemplate redis, ObjectMapper om) {
        return (toolName, args, ctx) -> {
            String name = (String) args.getOrDefault("name", "unnamed");
            String content = (String) args.getOrDefault("content", "");
            String type = (String) args.getOrDefault("type", "text");
            return Mono.fromCallable(() -> {
                        Map<String, Object> artifact = new LinkedHashMap<>();
                        artifact.put("name", name);
                        artifact.put("content", content);
                        artifact.put("type", type);
                        artifact.put("createdAt", System.currentTimeMillis());
                        String key = "artifact:" + ctx.getTenantId() + ":" + ctx.getTaskId() + ":" + name;
                        return new String[] { key, om.writeValueAsString(artifact) };
                    })
                    .flatMap(kv -> redis.opsForValue().set(kv[0], kv[1], java.time.Duration.ofDays(7))
                            .timeout(redisOpTimeout)
                            .thenReturn(toJson(om, Map.of("status", "saved", "name", name, "type", type, "size", content.length()))))
                    .onErrorResume(e -> Mono.just(toJson(om, Map.of("error", "Failed to save artifact: " + e.getMessage()))));
        };
    }

    private PlatformToolHandler getArtifactHandler(
            org.springframework.data.redis.core.ReactiveStringRedisTemplate redis, ObjectMapper om) {
        return (toolName, args, ctx) -> {
            String name = (String) args.getOrDefault("name", "");
            String key = "artifact:" + ctx.getTenantId() + ":" + ctx.getTaskId() + ":" + name;
            return redis.opsForValue().get(key)
                    .timeout(redisOpTimeout)
                    .switchIfEmpty(Mono.just(toJson(om, Map.of("error", "Artifact not found: " + name))))
                    .onErrorResume(e -> Mono.just(toJson(om, Map.of("error", "Failed to get artifact: " + e.getMessage()))));
        };
    }

    private static String toJson(ObjectMapper om, Object obj) {
        try { return om.writeValueAsString(obj); }
        catch (Exception e) { return "{\"error\":\"JSON serialization failed\"}"; }
    }

    private int toInt(Object val, int def) {
        if (val instanceof Number n) {
            return n.intValue();
        }
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                log.debug("toInt: not a valid integer '{}', using default {}", s, def);
            }
        }
        return def;
    }
}
