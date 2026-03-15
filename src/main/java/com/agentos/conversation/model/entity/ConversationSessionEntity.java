package com.agentos.conversation.model.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("conversation_sessions")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ConversationSessionEntity {

    @Id
    private UUID id;

    private UUID tenantId;
    private UUID userId;

    private String sessionType;
    private String interactionMode;

    private String boundEntityType;
    private UUID boundEntityId;

    private String title;

    @Builder.Default
    private String status = "active";

    private String activeContext;
    private String mcpToolConfig;
    private String conversationSummary;

    @Builder.Default
    private Integer messageCount = 0;

    @Builder.Default
    private Integer taskCount = 0;

    @Builder.Default
    private Long totalTokens = 0L;

    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Builder.Default
    private OffsetDateTime lastActivityAt = OffsetDateTime.now();
}
