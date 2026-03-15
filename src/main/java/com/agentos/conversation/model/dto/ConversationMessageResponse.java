package com.agentos.conversation.model.dto;

import com.agentos.conversation.model.entity.ConversationMessageEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversationMessageResponse {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE = new TypeReference<>() {};

    private UUID id;
    private UUID sessionId;
    private String role;
    private String content;
    private List<Map<String, Object>> contentBlocks;
    private List<Map<String, Object>> toolCalls;
    private String toolCallId;
    private UUID taskId;
    private String stepId;
    private Integer tokenCount;
    private Boolean pinned;
    private OffsetDateTime createdAt;

    public static ConversationMessageResponse fromEntity(ConversationMessageEntity e) {
        return ConversationMessageResponse.builder()
                .id(e.getId())
                .sessionId(e.getSessionId())
                .role(e.getRole())
                .content(e.getContent())
                .contentBlocks(parseJsonList(e.getContentBlocks()))
                .toolCalls(parseJsonList(e.getToolCalls()))
                .toolCallId(e.getToolCallId())
                .taskId(e.getTaskId())
                .stepId(e.getStepId())
                .tokenCount(e.getTokenCount())
                .pinned(e.getPinned())
                .createdAt(e.getCreatedAt())
                .build();
    }

    private static List<Map<String, Object>> parseJsonList(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readValue(json, LIST_TYPE); }
        catch (JsonProcessingException ex) { return null; }
    }
}
