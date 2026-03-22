package com.agentos.conversation.orchestration;

import com.agentos.common.model.ConsentScope;
import com.agentos.common.model.InteractionType;
import com.agentos.conversation.model.dto.SubmitRunInputRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps public {@link SubmitRunInputRequest} to Agent Runtime {@code HumanInteractionResponseDto} JSON.
 * Kept separate from {@link MessageOrchestrator} for unit testing and stable HITL wire contract.
 */
public final class RunHumanInputPayloadMapper {

    private RunHumanInputPayloadMapper() {
    }

    /**
     * @throws IllegalArgumentException if the request cannot be mapped or {@code consentScope} is invalid
     */
    public static Map<String, Object> toAgentRuntimeBody(SubmitRunInputRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        String token = request.getInteractionId();
        if (token != null && !token.isBlank()) {
            input.put("resumeToken", token);
        }

        if (request.getSelectedOptions() != null) {
            input.put("interactionType", InteractionType.PLAN_CONFIRMATION.getValue());
            input.put("approved", request.getApproved() != null ? request.getApproved() : Boolean.TRUE);
            input.put("responsePayload", Map.of("selectedOptions", request.getSelectedOptions()));
            appendConsentScope(input, request);
            return input;
        }

        String content = request.getContent();
        if (content != null && !content.isBlank()) {
            input.put("interactionType", InteractionType.QUESTION_INPUT.getValue());
            input.put("userInput", content);
            if (request.getApproved() != null) {
                input.put("approved", request.getApproved());
            }
            appendConsentScope(input, request);
            return input;
        }

        if (request.getApproved() != null) {
            input.put("interactionType", InteractionType.SIMPLE_APPROVAL.getValue());
            input.put("approved", request.getApproved());
            appendConsentScope(input, request);
            return input;
        }

        throw new IllegalArgumentException(
                "Run input must include approved, content, or selectedOptions");
    }

    private static void appendConsentScope(Map<String, Object> input, SubmitRunInputRequest request) {
        String raw = request.getConsentScope();
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            ConsentScope scope = ConsentScope.fromValue(raw);
            input.put("consentScope", scope.getValue());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid consentScope: " + raw);
        }
    }
}
