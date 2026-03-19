package com.agentos.conversation.service;

import com.agentos.conversation.context.ConversationSummarizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunStatusSyncServiceTest {

    @Mock ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock ReactiveValueOperations<String, String> valueOps;
    @Mock RunService runService;
    @Mock ConversationSummarizer summarizer;

    RunStatusSyncService service;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new RunStatusSyncService(redisTemplate, runService, summarizer, objectMapper);
    }

    // ---- handleTaskEvent ----

    @Nested
    class HandleTaskEvent {

        @Test
        void taskCompleted_syncsStatusAndTriggersSummarization() throws Exception {
            UUID runId = UUID.randomUUID();
            String taskId = UUID.randomUUID().toString();
            UUID sessionId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();

            Map<String, Object> event = Map.of("eventType", "task.completed", "taskId", taskId);
            String eventJson = objectMapper.writeValueAsString(event);

            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("run:task:" + taskId)).thenReturn(Mono.just(runId.toString()));
            when(runService.updateStatus(runId, "completed")).thenReturn(Mono.empty());

            when(valueOps.get("run:task:" + taskId + ":session")).thenReturn(Mono.just(sessionId.toString()));
            when(valueOps.get("run:task:" + taskId + ":tenant")).thenReturn(Mono.just(tenantId.toString()));
            when(summarizer.summarizeIfNeeded(sessionId, tenantId)).thenReturn(Mono.empty());

            @SuppressWarnings("unchecked")
            ReactiveSubscription.Message<String, String> message = mock(ReactiveSubscription.Message.class);
            when(message.getMessage()).thenReturn(eventJson);

            ReflectionTestUtils.invokeMethod(service, "handleTaskEvent", message);

            // Verify sync was triggered (subscribe is called internally)
            verify(valueOps).get("run:task:" + taskId);
        }

        @Test
        void taskFailed_failsRunWithError() throws Exception {
            String taskId = UUID.randomUUID().toString();
            UUID runId = UUID.randomUUID();

            Map<String, Object> event = Map.of(
                    "eventType", "task.failed",
                    "taskId", taskId,
                    "errorCode", "TIMEOUT",
                    "errorMessage", "Execution timed out");
            String eventJson = objectMapper.writeValueAsString(event);

            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("run:task:" + taskId)).thenReturn(Mono.just(runId.toString()));
            when(runService.failRun(eq(runId), eq("TIMEOUT"), eq("Execution timed out"), eq(false)))
                    .thenReturn(Mono.empty());

            @SuppressWarnings("unchecked")
            ReactiveSubscription.Message<String, String> message = mock(ReactiveSubscription.Message.class);
            when(message.getMessage()).thenReturn(eventJson);

            ReflectionTestUtils.invokeMethod(service, "handleTaskEvent", message);

            verify(valueOps).get("run:task:" + taskId);
        }

        @Test
        void taskCancelled_syncsStatus() throws Exception {
            String taskId = UUID.randomUUID().toString();
            UUID runId = UUID.randomUUID();

            Map<String, Object> event = Map.of("eventType", "task.cancelled", "taskId", taskId);
            String eventJson = objectMapper.writeValueAsString(event);

            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("run:task:" + taskId)).thenReturn(Mono.just(runId.toString()));
            when(runService.updateStatus(runId, "cancelled")).thenReturn(Mono.empty());

            @SuppressWarnings("unchecked")
            ReactiveSubscription.Message<String, String> message = mock(ReactiveSubscription.Message.class);
            when(message.getMessage()).thenReturn(eventJson);

            ReflectionTestUtils.invokeMethod(service, "handleTaskEvent", message);

            verify(valueOps).get("run:task:" + taskId);
        }

        @Test
        void taskTimeout_failsRunWithTimeoutCode() throws Exception {
            String taskId = UUID.randomUUID().toString();
            UUID runId = UUID.randomUUID();

            Map<String, Object> event = Map.of("eventType", "task.timeout", "taskId", taskId);
            String eventJson = objectMapper.writeValueAsString(event);

            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("run:task:" + taskId)).thenReturn(Mono.just(runId.toString()));
            when(runService.failRun(eq(runId), eq("TIMEOUT"), eq("Task execution timed out"), eq(false)))
                    .thenReturn(Mono.empty());

            @SuppressWarnings("unchecked")
            ReactiveSubscription.Message<String, String> message = mock(ReactiveSubscription.Message.class);
            when(message.getMessage()).thenReturn(eventJson);

            ReflectionTestUtils.invokeMethod(service, "handleTaskEvent", message);

            verify(valueOps).get("run:task:" + taskId);
        }

        @Test
        void nullTaskId_doesNothing() throws Exception {
            Map<String, Object> event = Map.of("eventType", "task.completed");
            String eventJson = objectMapper.writeValueAsString(event);

            @SuppressWarnings("unchecked")
            ReactiveSubscription.Message<String, String> message = mock(ReactiveSubscription.Message.class);
            when(message.getMessage()).thenReturn(eventJson);

            ReflectionTestUtils.invokeMethod(service, "handleTaskEvent", message);

            verifyNoInteractions(runService);
        }

        @Test
        void nullEventType_doesNothing() throws Exception {
            Map<String, Object> event = Map.of("taskId", UUID.randomUUID().toString());
            String eventJson = objectMapper.writeValueAsString(event);

            @SuppressWarnings("unchecked")
            ReactiveSubscription.Message<String, String> message = mock(ReactiveSubscription.Message.class);
            when(message.getMessage()).thenReturn(eventJson);

            ReflectionTestUtils.invokeMethod(service, "handleTaskEvent", message);

            verifyNoInteractions(runService);
        }

        @Test
        void malformedJson_doesNotThrow() {
            @SuppressWarnings("unchecked")
            ReactiveSubscription.Message<String, String> message = mock(ReactiveSubscription.Message.class);
            when(message.getMessage()).thenReturn("not valid json");

            ReflectionTestUtils.invokeMethod(service, "handleTaskEvent", message);

            verifyNoInteractions(runService);
        }

        @Test
        void unknownEventType_doesNothing() throws Exception {
            Map<String, Object> event = Map.of(
                    "eventType", "task.unknown",
                    "taskId", UUID.randomUUID().toString());
            String eventJson = objectMapper.writeValueAsString(event);

            @SuppressWarnings("unchecked")
            ReactiveSubscription.Message<String, String> message = mock(ReactiveSubscription.Message.class);
            when(message.getMessage()).thenReturn(eventJson);

            ReflectionTestUtils.invokeMethod(service, "handleTaskEvent", message);

            verifyNoInteractions(runService);
        }
    }

    // ---- syncRunStatus (private) ----

    @Nested
    class SyncRunStatus {

        @Test
        void noRunMapping_doesNothing() {
            String taskId = UUID.randomUUID().toString();

            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("run:task:" + taskId)).thenReturn(Mono.empty());

            Mono<Void> result = ReflectionTestUtils.invokeMethod(
                    service, "syncRunStatus", taskId, "completed");

            result.block();
            verify(runService, never()).updateStatus(any(), any());
        }
    }

    // ---- triggerSummarizationForTask (private) ----

    @Nested
    class TriggerSummarization {

        @Test
        void missingSessionMapping_skips() {
            String taskId = UUID.randomUUID().toString();

            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("run:task:" + taskId + ":session")).thenReturn(Mono.just(""));
            when(valueOps.get("run:task:" + taskId + ":tenant")).thenReturn(Mono.just(""));

            Mono<Void> result = ReflectionTestUtils.invokeMethod(
                    service, "triggerSummarizationForTask", taskId);

            result.block();
            verify(summarizer, never()).summarizeIfNeeded(any(), any());
        }

        @Test
        void validMapping_triggersSummarizer() {
            String taskId = UUID.randomUUID().toString();
            UUID sessionId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();

            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("run:task:" + taskId + ":session")).thenReturn(Mono.just(sessionId.toString()));
            when(valueOps.get("run:task:" + taskId + ":tenant")).thenReturn(Mono.just(tenantId.toString()));
            when(summarizer.summarizeIfNeeded(sessionId, tenantId)).thenReturn(Mono.empty());

            Mono<Void> result = ReflectionTestUtils.invokeMethod(
                    service, "triggerSummarizationForTask", taskId);

            result.block();
            verify(summarizer).summarizeIfNeeded(sessionId, tenantId);
        }
    }
}
