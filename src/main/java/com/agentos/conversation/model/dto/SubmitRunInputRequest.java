package com.agentos.conversation.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitRunInputRequest {

    @NotBlank
    private String interactionId;

    private String content;
    private Boolean approved;
    private List<String> selectedOptions;
}
