package com.agentos.conversation.model.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("runs")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RunEntity {

    @Id
    private UUID id;

    private UUID sessionId;
    private UUID tenantId;
    private UUID userId;

    @Builder.Default
    private String status = "queued";

    private String routeType;
    private UUID taskId;
    private UUID inputMessageId;
    private UUID outputMessageId;
    private String modelHint;

    private String tokenUsage;
    private String error;
    private String metadata;

    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private String lastEventId;

    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
