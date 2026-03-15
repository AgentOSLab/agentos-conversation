package com.agentos.conversation.model.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("conversation_messages")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ConversationMessageEntity {

    @Id
    private UUID id;

    private UUID sessionId;

    private String role;

    private String content;
    private String contentBlocks;
    private String toolCalls;
    private String toolCallId;

    private UUID taskId;
    private String stepId;

    private Integer tokenCount;
    private String metadata;

    @Builder.Default
    private Boolean pinned = false;

    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
