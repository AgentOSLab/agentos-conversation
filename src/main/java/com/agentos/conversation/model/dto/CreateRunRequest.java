package com.agentos.conversation.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRunRequest {

    @NotBlank
    @Size(max = 100000)
    private String content;

    private List<Map<String, Object>> attachments;

    @Builder.Default
    private boolean stream = true;

    /**
     * LLM Gateway compliance routing ({@code dataSensitivity}). When unset, callers default to {@code standard}.
     */
    private String dataSensitivity;
}
