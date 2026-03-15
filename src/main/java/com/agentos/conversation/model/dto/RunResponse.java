package com.agentos.conversation.model.dto;

import com.agentos.conversation.model.entity.RunEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunResponse {

    private UUID runId;
    private UUID sessionId;
    private String status;
    private String routeType;
    private UUID taskId;
    private String outputContent;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;

    public static RunResponse fromEntity(RunEntity entity) {
        return RunResponse.builder()
                .runId(entity.getId())
                .sessionId(entity.getSessionId())
                .status(entity.getStatus())
                .routeType(entity.getRouteType())
                .taskId(entity.getTaskId())
                .createdAt(entity.getCreatedAt())
                .completedAt(entity.getCompletedAt())
                .build();
    }
}
