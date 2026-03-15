package com.agentos.conversation.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSessionRequest {

    @NotNull
    private String sessionType;

    @Builder.Default
    private String interactionMode = "INTERACTIVE";

    private String boundEntityType;
    private UUID boundEntityId;
    private String title;
    private Map<String, Object> mcpToolConfig;
}
