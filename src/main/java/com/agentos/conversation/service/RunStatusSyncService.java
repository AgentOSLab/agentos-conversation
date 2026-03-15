package com.agentos.conversation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Subscribes to Agent Runtime task completion/failure events via Redis pub/sub,
 * and synchronizes Run status accordingly.
 *
 * Agent Runtime publishes to channel: "agentos:task:events"
 * This service looks up taskId -> runId mapping in Redis and updates Run status.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunStatusSyncService {

    private static final String TASK_EVENTS_CHANNEL = "agentos:task:events";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RunService runService;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void startListening() {
        redisTemplate.listenTo(ChannelTopic.of(TASK_EVENTS_CHANNEL))
                .doOnNext(this::handleTaskEvent)
                .doOnError(e -> log.error("Task event subscription error: {}", e.getMessage()))
                .subscribe();

        log.info("RunStatusSyncService started listening on channel: {}", TASK_EVENTS_CHANNEL);
    }

    private void handleTaskEvent(ReactiveSubscription.Message<String, String> message) {
        try {
            Map<String, Object> event = objectMapper.readValue(
                    message.getMessage(), new TypeReference<>() {});

            String eventType = (String) event.get("eventType");
            String taskId = (String) event.get("taskId");

            if (taskId == null || eventType == null) return;

            switch (eventType) {
                case "task.completed" -> {
                    log.info("Task completed, syncing run status: taskId={}", taskId);
                    syncRunStatus(taskId, "completed").subscribe();
                }
                case "task.failed" -> {
                    String errorCode = (String) event.getOrDefault("errorCode", "UNKNOWN");
                    String errorMessage = (String) event.getOrDefault("errorMessage", "");
                    log.info("Task failed, syncing run status: taskId={}", taskId);
                    failRunByTaskId(taskId, errorCode, errorMessage).subscribe();
                }
                case "task.cancelled" -> {
                    log.info("Task cancelled, syncing run status: taskId={}", taskId);
                    syncRunStatus(taskId, "cancelled").subscribe();
                }
                case "task.timeout" -> {
                    log.info("Task timed out, syncing run status: taskId={}", taskId);
                    failRunByTaskId(taskId, "TIMEOUT", "Task execution timed out").subscribe();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to process task event: {}", e.getMessage());
        }
    }

    private Mono<Void> syncRunStatus(String taskId, String status) {
        String mappingKey = "run:task:" + taskId;
        return redisTemplate.opsForValue().get(mappingKey)
                .flatMap(runIdStr -> {
                    UUID runId = UUID.fromString(runIdStr);
                    return runService.updateStatus(runId, status);
                })
                .doOnError(e -> log.warn("Failed to sync run status for task {}: {}",
                        taskId, e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    private Mono<Void> failRunByTaskId(String taskId, String code, String message) {
        String mappingKey = "run:task:" + taskId;
        return redisTemplate.opsForValue().get(mappingKey)
                .flatMap(runIdStr -> {
                    UUID runId = UUID.fromString(runIdStr);
                    return runService.failRun(runId, code, message, false);
                })
                .doOnError(e -> log.warn("Failed to fail run for task {}: {}",
                        taskId, e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }
}
