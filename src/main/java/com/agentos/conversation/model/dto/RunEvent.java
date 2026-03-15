package com.agentos.conversation.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Base envelope for all SSE run events. Each event maps to an SSE frame:
 *   id: {eventId}
 *   event: {type}
 *   data: {JSON of this object}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunEvent {

    private String eventId;
    private UUID runId;
    private UUID sessionId;
    private String type;
    private OffsetDateTime timestamp;
    private DisplayMetadata display;

    private String content;
    private Integer index;

    private String toolCallId;
    private String toolName;
    private Map<String, Object> arguments;
    private Map<String, Object> result;

    private String interactionId;
    private String interactionType;
    private String prompt;

    private UUID messageId;
    private Map<String, Object> usage;
    private Map<String, Object> contextUsage;
    private Map<String, Object> error;

    private String finalStatus;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DisplayMetadata {
        private String category;
        private String priority;
        private String summary;
        private String detail;
    }
}
