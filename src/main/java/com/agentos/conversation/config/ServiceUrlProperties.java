package com.agentos.conversation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agentos.services")
public class ServiceUrlProperties {

    private String agentRuntimeUrl = "http://localhost:8004";
    /** Workflow Runtime internal API base (DAG executions). */
    private String workflowRuntimeUrl = "http://localhost:8015";
    private String hubUrl = "http://localhost:8001";
    private String llmGatewayUrl = "http://localhost:8003";
    // NOTE: ragUrl intentionally absent — Conversation does not call RAG directly (ADR-046).
    // RAG retrieval is performed by Agent Runtime's RagRetrievalTool during task execution.
    private String userSystemUrl = "http://localhost:8006";
}
