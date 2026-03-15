package com.agentos.conversation.model.dto;

import com.agentos.conversation.model.entity.ConversationSessionEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversationSessionResponse {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private UUID id;
    private UUID tenantId;
    private UUID userId;
    private String sessionType;
    private String interactionMode;
    private String boundEntityType;
    private UUID boundEntityId;
    private String title;
    private String status;
    private Map<String, Object> activeContext;
    private Map<String, Object> mcpToolConfig;
    private String conversationSummary;
    private Integer messageCount;
    private Integer taskCount;
    private Long totalTokens;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime lastActivityAt;

    public static ConversationSessionResponse fromEntity(ConversationSessionEntity e) {
        return ConversationSessionResponse.builder()
                .id(e.getId())
                .tenantId(e.getTenantId())
                .userId(e.getUserId())
                .sessionType(e.getSessionType())
                .interactionMode(e.getInteractionMode())
                .boundEntityType(e.getBoundEntityType())
                .boundEntityId(e.getBoundEntityId())
                .title(e.getTitle())
                .status(e.getStatus())
                .activeContext(parseJson(e.getActiveContext()))
                .mcpToolConfig(parseJson(e.getMcpToolConfig()))
                .conversationSummary(e.getConversationSummary())
                .messageCount(e.getMessageCount())
                .taskCount(e.getTaskCount())
                .totalTokens(e.getTotalTokens())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .lastActivityAt(e.getLastActivityAt())
                .build();
    }

    private static Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readValue(json, MAP_TYPE); }
        catch (JsonProcessingException ex) { return null; }
    }
}
