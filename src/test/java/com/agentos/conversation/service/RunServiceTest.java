package com.agentos.conversation.service;

import com.agentos.conversation.model.dto.RunResponse;
import com.agentos.conversation.model.entity.RunEntity;
import com.agentos.conversation.repository.RunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunServiceTest {

    @Mock RunRepository runRepository;

    ObjectMapper objectMapper = new ObjectMapper();
    RunService runService;

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @BeforeEach
    void setUp() {
        runService = new RunService(runRepository, objectMapper);
    }

    @Test
    void createRun_savesEntity() {
        when(runRepository.save(any())).thenAnswer(inv -> {
            RunEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return Mono.just(e);
        });

        Mono<RunEntity> result = runService.createRun(
                SESSION_ID, TENANT_ID, USER_ID, "simple_chat", UUID.randomUUID());

        StepVerifier.create(result)
                .assertNext(run -> {
                    assertThat(run.getSessionId()).isEqualTo(SESSION_ID);
                    assertThat(run.getTenantId()).isEqualTo(TENANT_ID);
                    assertThat(run.getUserId()).isEqualTo(USER_ID);
                    assertThat(run.getRouteType()).isEqualTo("simple_chat");
                    assertThat(run.getStatus()).isEqualTo("queued");
                })
                .verifyComplete();

        ArgumentCaptor<RunEntity> captor = ArgumentCaptor.forClass(RunEntity.class);
        verify(runRepository).save(captor.capture());
        RunEntity saved = captor.getValue();
        assertThat(saved.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getRouteType()).isEqualTo("simple_chat");
    }

    @Test
    void getRun_returnsEntity() {
        RunEntity entity = RunEntity.builder()
                .id(RUN_ID)
                .sessionId(SESSION_ID)
                .tenantId(TENANT_ID)
                .userId(USER_ID)
                .status("completed")
                .routeType("simple_chat")
                .build();

        when(runRepository.findByIdAndTenantId(RUN_ID, TENANT_ID)).thenReturn(Mono.just(entity));

        StepVerifier.create(runService.getRun(RUN_ID, TENANT_ID))
                .assertNext(run -> {
                    assertThat(run.getId()).isEqualTo(RUN_ID);
                    assertThat(run.getStatus()).isEqualTo("completed");
                })
                .verifyComplete();

        verify(runRepository).findByIdAndTenantId(RUN_ID, TENANT_ID);
    }

    @Test
    void listRuns_returnsPageResponse() {
        RunEntity e1 = RunEntity.builder().id(UUID.randomUUID()).sessionId(SESSION_ID).tenantId(TENANT_ID).build();
        RunEntity e2 = RunEntity.builder().id(UUID.randomUUID()).sessionId(SESSION_ID).tenantId(TENANT_ID).build();

        when(runRepository.countBySession(SESSION_ID, TENANT_ID)).thenReturn(Mono.just(2L));
        when(runRepository.findBySession(eq(SESSION_ID), eq(TENANT_ID), eq(20), anyLong()))
                .thenReturn(Flux.just(e1, e2));

        StepVerifier.create(runService.listRuns(SESSION_ID, TENANT_ID, 20, 0))
                .assertNext(page -> {
                    assertThat(page.getTotalCount()).isEqualTo(2);
                    assertThat(page.getItems()).hasSize(2);
                    assertThat(page.getItems()).allMatch(r -> r instanceof RunResponse);
                })
                .verifyComplete();

        verify(runRepository).countBySession(SESSION_ID, TENANT_ID);
        verify(runRepository).findBySession(SESSION_ID, TENANT_ID, 20, 0);
    }

    @Test
    void markRunning_delegatesToRepo() {
        when(runRepository.markRunning(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(runService.markRunning(RUN_ID))
                .verifyComplete();

        ArgumentCaptor<OffsetDateTime> timeCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(runRepository).markRunning(eq(RUN_ID), timeCaptor.capture());
        assertThat(timeCaptor.getValue()).isNotNull();
    }

    @Test
    void setTaskId_delegatesToRepo() {
        UUID taskId = UUID.randomUUID();
        when(runRepository.setTaskId(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(runService.setTaskId(RUN_ID, taskId))
                .verifyComplete();

        verify(runRepository).setTaskId(RUN_ID, taskId);
    }

    @Test
    void updateStatus_delegatesToRepo() {
        when(runRepository.updateStatus(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(runService.updateStatus(RUN_ID, "running"))
                .verifyComplete();

        verify(runRepository).updateStatus(RUN_ID, "running");
    }

    @Test
    void completeRun_serializesTokenUsage() {
        UUID outputMsgId = UUID.randomUUID();
        Map<String, Object> tokenUsage = Map.of(
                "promptTokens", 100,
                "completionTokens", 50,
                "totalTokens", 150
        );

        when(runRepository.completeRun(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(runService.completeRun(RUN_ID, outputMsgId, tokenUsage, "run:seq:000001"))
                .verifyComplete();

        ArgumentCaptor<String> tokenUsageCaptor = ArgumentCaptor.forClass(String.class);
        verify(runRepository).completeRun(
                eq(RUN_ID),
                eq("completed"),
                eq(outputMsgId),
                tokenUsageCaptor.capture(),
                eq("run:seq:000001"),
                any(OffsetDateTime.class)
        );

        String json = tokenUsageCaptor.getValue();
        assertThat(json).contains("promptTokens");
        assertThat(json).contains("100");
        assertThat(json).contains("completionTokens");
        assertThat(json).contains("50");
    }

    @Test
    void failRun_serializesError() {
        when(runRepository.failRun(any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(runService.failRun(RUN_ID, "AGENT_NOT_READY", "Setup required", true))
                .verifyComplete();

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(runRepository).failRun(eq(RUN_ID), errorCaptor.capture(), any(OffsetDateTime.class));

        String errorJson = errorCaptor.getValue();
        assertThat(errorJson).contains("AGENT_NOT_READY");
        assertThat(errorJson).contains("Setup required");
        assertThat(errorJson).contains("retryable");
    }

    @Test
    void cancelRun_completesWithCancelledStatus() {
        when(runRepository.completeRun(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(runService.cancelRun(RUN_ID))
                .verifyComplete();

        verify(runRepository).completeRun(
                eq(RUN_ID),
                eq("cancelled"),
                isNull(),
                isNull(),
                isNull(),
                any(OffsetDateTime.class)
        );
    }

    @Test
    void updateLastEventId_delegatesToRepo() {
        when(runRepository.updateLastEventId(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(runService.updateLastEventId(RUN_ID, "run:seq:000042"))
                .verifyComplete();

        verify(runRepository).updateLastEventId(RUN_ID, "run:seq:000042");
    }
}
