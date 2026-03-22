package com.agentos.conversation.orchestration;

import com.agentos.conversation.model.dto.SubmitRunInputRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunHumanInputPayloadMapperTest {

    @Test
    void simpleApproval_mapsInteractionTypeResumeTokenAndApproved() {
        SubmitRunInputRequest req = SubmitRunInputRequest.builder()
                .interactionId("hitl-1")
                .approved(true)
                .build();
        Map<String, Object> m = RunHumanInputPayloadMapper.toAgentRuntimeBody(req);
        assertThat(m)
                .containsEntry("interactionType", "simple_approval")
                .containsEntry("approved", true)
                .containsEntry("resumeToken", "hitl-1");
    }

    @Test
    void questionInput_mapsUserInputAndOptionalApproved() {
        SubmitRunInputRequest req = SubmitRunInputRequest.builder()
                .interactionId("q-1")
                .content("My answer")
                .approved(true)
                .build();
        Map<String, Object> m = RunHumanInputPayloadMapper.toAgentRuntimeBody(req);
        assertThat(m)
                .containsEntry("interactionType", "question_input")
                .containsEntry("userInput", "My answer")
                .containsEntry("approved", true)
                .containsEntry("resumeToken", "q-1");
    }

    @Test
    void planConfirmation_defaultsApprovedTrueAndWrapsSelectedOptions() {
        SubmitRunInputRequest req = SubmitRunInputRequest.builder()
                .interactionId("p-1")
                .selectedOptions(List.of("step-a", "step-b"))
                .build();
        Map<String, Object> m = RunHumanInputPayloadMapper.toAgentRuntimeBody(req);
        assertThat(m)
                .containsEntry("interactionType", "plan_confirmation")
                .containsEntry("approved", true)
                .containsKey("responsePayload");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) m.get("responsePayload");
        assertThat(payload).containsEntry("selectedOptions", List.of("step-a", "step-b"));
    }

    @Test
    void consentScope_normalizedToWireLowercase() {
        SubmitRunInputRequest req = SubmitRunInputRequest.builder()
                .interactionId("x")
                .approved(true)
                .consentScope("SESSION")
                .build();
        Map<String, Object> m = RunHumanInputPayloadMapper.toAgentRuntimeBody(req);
        assertThat(m).containsEntry("consentScope", "session");
    }

    @Test
    void missingApprovedContentAndSelectedOptions_throws() {
        assertThatThrownBy(() -> RunHumanInputPayloadMapper.toAgentRuntimeBody(
                        SubmitRunInputRequest.builder().interactionId("only").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Run input must include");
    }

    @Test
    void invalidConsentScope_throws() {
        SubmitRunInputRequest req = SubmitRunInputRequest.builder()
                .interactionId("x")
                .approved(true)
                .consentScope("forever")
                .build();
        assertThatThrownBy(() -> RunHumanInputPayloadMapper.toAgentRuntimeBody(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid consentScope");
    }
}
