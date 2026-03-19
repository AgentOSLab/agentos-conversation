package com.agentos.conversation.context;

import com.agentos.conversation.context.SessionContextBuilder.AssembledContext;
import com.agentos.conversation.context.SessionContextBuilder.ContextRequest;
import com.agentos.conversation.model.entity.ConversationMessageEntity;
import com.agentos.conversation.service.ConversationSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class SessionContextBuilderTest {

    @Mock ConversationSessionService sessionService;
    @Mock ReactiveStringRedisTemplate redisTemplate;

    ObjectMapper objectMapper = new ObjectMapper();
    SessionContextBuilder builder;

    @BeforeEach
    void setUp() {
        ModelContextService modelContextService = mock(ModelContextService.class);

        builder = new SessionContextBuilder(sessionService, modelContextService, redisTemplate, objectMapper);
        ReflectionTestUtils.setField(builder, "defaultWindowSize", 20);
        ReflectionTestUtils.setField(builder, "maxTokenBudget", 100000);
        ReflectionTestUtils.setField(builder, "pinnedMessagesLimit", 10);
        ReflectionTestUtils.setField(builder, "crossSessionMemoryLimit", 3);
        ReflectionTestUtils.setField(builder, "toolOutputMaxChars", 2000);
        ReflectionTestUtils.setField(builder, "cacheTtlMinutes", 5);
    }

    @Nested
    class EstimateTokens {

        @Test
        void estimatesBasedOnCharDivFour() {
            Map<String, Object> msg = Map.of("content", "hello world!");
            int tokens = ReflectionTestUtils.invokeMethod(builder, "estimateTokens", msg);
            assertThat(tokens).isEqualTo(3); // 12 chars / 4
        }

        @Test
        void emptyContent_returnsZero() {
            Map<String, Object> msg = Map.of("role", "user");
            int tokens = ReflectionTestUtils.invokeMethod(builder, "estimateTokens", msg);
            assertThat(tokens).isEqualTo(0);
        }

        @Test
        void listOverload_sumsTokens() {
            List<Map<String, Object>> messages = List.of(
                    Map.of("content", "1234567890123456"), // 16/4=4
                    Map.of("content", "12345678")           // 8/4=2
            );
            int total = ReflectionTestUtils.invokeMethod(builder, "estimateTokens", messages);
            assertThat(total).isEqualTo(6);
        }
    }

    @Nested
    class CompressToolOutput {

        @Test
        void compressesLongToolOutput() {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "tool");
            msg.put("content", "x".repeat(5000));

            ReflectionTestUtils.invokeMethod(builder, "compressToolOutputIfNeeded", msg);

            String content = (String) msg.get("content");
            assertThat(content.length()).isLessThan(5000);
            assertThat(content).contains("...[truncated");
            assertThat(content).contains("chars]...");
        }

        @Test
        void doesNotCompressShortOutput() {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "tool");
            msg.put("content", "short output");

            ReflectionTestUtils.invokeMethod(builder, "compressToolOutputIfNeeded", msg);

            assertThat(msg.get("content")).isEqualTo("short output");
        }

        @Test
        void doesNotCompressNonToolMessages() {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "assistant");
            msg.put("content", "x".repeat(5000));

            ReflectionTestUtils.invokeMethod(builder, "compressToolOutputIfNeeded", msg);

            assertThat(((String) msg.get("content")).length()).isEqualTo(5000);
        }

        @Test
        void preservesStartAndEnd() {
            String original = "START" + "m".repeat(4990) + "END!!";
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "tool");
            msg.put("content", original);

            ReflectionTestUtils.invokeMethod(builder, "compressToolOutputIfNeeded", msg);

            String compressed = (String) msg.get("content");
            assertThat(compressed).startsWith("START");
            assertThat(compressed).endsWith("END!!");
        }
    }

    @Nested
    class ShouldExcludeFromContext {

        @Test
        void excludesThinkingMessages() throws Exception {
            ConversationMessageEntity msg = new ConversationMessageEntity();
            msg.setMetadata(objectMapper.writeValueAsString(Map.of("contentType", "thinking")));

            boolean result = ReflectionTestUtils.invokeMethod(builder, "shouldExcludeFromContext", msg);
            assertThat(result).isTrue();
        }

        @Test
        void excludesReasoningMessages() throws Exception {
            ConversationMessageEntity msg = new ConversationMessageEntity();
            msg.setMetadata(objectMapper.writeValueAsString(Map.of("contentType", "reasoning")));

            boolean result = ReflectionTestUtils.invokeMethod(builder, "shouldExcludeFromContext", msg);
            assertThat(result).isTrue();
        }

        @Test
        void doesNotExcludeNormalMessages() throws Exception {
            ConversationMessageEntity msg = new ConversationMessageEntity();
            msg.setMetadata(objectMapper.writeValueAsString(Map.of("contentType", "text")));

            boolean result = ReflectionTestUtils.invokeMethod(builder, "shouldExcludeFromContext", msg);
            assertThat(result).isFalse();
        }

        @Test
        void doesNotExclude_nullMetadata() {
            ConversationMessageEntity msg = new ConversationMessageEntity();
            msg.setMetadata(null);

            boolean result = ReflectionTestUtils.invokeMethod(builder, "shouldExcludeFromContext", msg);
            assertThat(result).isFalse();
        }

        @Test
        void doesNotExclude_invalidJson() {
            ConversationMessageEntity msg = new ConversationMessageEntity();
            msg.setMetadata("not-valid-json");

            boolean result = ReflectionTestUtils.invokeMethod(builder, "shouldExcludeFromContext", msg);
            assertThat(result).isFalse();
        }

        @Test
        void doesNotExclude_noContentType() throws Exception {
            ConversationMessageEntity msg = new ConversationMessageEntity();
            msg.setMetadata(objectMapper.writeValueAsString(Map.of("key", "value")));

            boolean result = ReflectionTestUtils.invokeMethod(builder, "shouldExcludeFromContext", msg);
            assertThat(result).isFalse();
        }
    }

    @Nested
    class MessageToMap {

        @Test
        void mapsRoleAndContent() {
            ConversationMessageEntity msg = new ConversationMessageEntity();
            msg.setRole("user");
            msg.setContent("Hello");

            Map<String, Object> result = ReflectionTestUtils.invokeMethod(builder, "messageToMap", msg);
            assertThat(result).containsEntry("role", "user");
            assertThat(result).containsEntry("content", "Hello");
        }

        @Test
        void mapsToolCallId() {
            ConversationMessageEntity msg = new ConversationMessageEntity();
            msg.setRole("tool");
            msg.setContent("result");
            msg.setToolCallId("call_abc123");

            Map<String, Object> result = ReflectionTestUtils.invokeMethod(builder, "messageToMap", msg);
            assertThat(result).containsEntry("tool_call_id", "call_abc123");
        }

        @Test
        void mapsToolCalls() {
            ConversationMessageEntity msg = new ConversationMessageEntity();
            msg.setRole("assistant");
            msg.setToolCalls("[{\"id\":\"call_1\",\"function\":{\"name\":\"search\"}}]");

            Map<String, Object> result = ReflectionTestUtils.invokeMethod(builder, "messageToMap", msg);
            assertThat(result).containsKey("tool_calls");
        }

        @Test
        void nullContent_notIncluded() {
            ConversationMessageEntity msg = new ConversationMessageEntity();
            msg.setRole("assistant");
            msg.setContent(null);

            Map<String, Object> result = ReflectionTestUtils.invokeMethod(builder, "messageToMap", msg);
            assertThat(result).containsEntry("role", "assistant");
            assertThat(result).doesNotContainKey("content");
        }
    }

    @Nested
    class BuildStandaloneContext {

        @Test
        void noSession_buildsMinimalContext() {
            ContextRequest request = ContextRequest.builder()
                    .agentPersona("You are a helpful assistant")
                    .currentUserMessage("Hello")
                    .build();

            AssembledContext ctx = ReflectionTestUtils.invokeMethod(builder, "buildStandaloneContext", request);

            assertThat(ctx).isNotNull();
            assertThat(ctx.getMessages()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(ctx.getMessages().get(0).get("role")).isEqualTo("system");
            assertThat(ctx.getMessages().get(0)).containsKey("cache_control");
            Map<String, Object> lastMsg = ctx.getMessages().get(ctx.getMessages().size() - 1);
            assertThat(lastMsg).containsEntry("role", "user");
            assertThat(lastMsg).containsEntry("content", "Hello");
            assertThat(ctx.isHasSummary()).isFalse();
        }

        @Test
        void includesConversationHistory() {
            List<Map<String, Object>> history = List.of(
                    Map.of("role", "user", "content", "prior"),
                    Map.of("role", "assistant", "content", "response")
            );
            ContextRequest request = ContextRequest.builder()
                    .conversationHistory(history)
                    .currentUserMessage("next")
                    .build();

            AssembledContext ctx = ReflectionTestUtils.invokeMethod(builder, "buildStandaloneContext", request);

            assertThat(ctx.getMessages().size()).isGreaterThanOrEqualTo(4);
            assertThat(ctx.getMessageCountInWindow()).isEqualTo(ctx.getMessages().size());
        }

        @Test
        void estimatedTokensArePopulated() {
            ContextRequest request = ContextRequest.builder()
                    .agentPersona("persona " + "x".repeat(400))
                    .currentUserMessage("test")
                    .build();

            AssembledContext ctx = ReflectionTestUtils.invokeMethod(builder, "buildStandaloneContext", request);

            assertThat(ctx.getEstimatedTokens()).isGreaterThan(0);
            assertThat(ctx.getMaxContextTokens()).isEqualTo(100000);
            assertThat(ctx.getContextUsagePercent()).isGreaterThanOrEqualTo(0.0);
        }
    }

    @Nested
    class BuildSystemMessage {

        @Test
        void combinesPersonaAndModeRules() {
            ContextRequest request = ContextRequest.builder()
                    .agentPersona("You are a helper")
                    .executionModeRules("Mode: direct execution")
                    .build();

            Map<String, Object> msg = ReflectionTestUtils.invokeMethod(
                    builder, "buildSystemMessage", request, null);

            String content = (String) msg.get("content");
            assertThat(content).contains("You are a helper");
            assertThat(content).contains("Mode: direct execution");
            assertThat(msg.get("role")).isEqualTo("system");
        }

        @Test
        void nullPersona_emptyContent() {
            ContextRequest request = ContextRequest.builder().build();

            Map<String, Object> msg = ReflectionTestUtils.invokeMethod(
                    builder, "buildSystemMessage", request, null);

            assertThat(msg.get("role")).isEqualTo("system");
        }
    }
}
