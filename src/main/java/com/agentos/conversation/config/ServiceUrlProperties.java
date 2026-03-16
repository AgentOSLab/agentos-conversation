package com.agentos.conversation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agentos.services")
public class ServiceUrlProperties {

    private String agentRuntimeUrl = "http://localhost:8004";
    private String hubUrl = "http://localhost:8001";
    private String llmGatewayUrl = "http://localhost:8003";
    private String ragUrl = "http://localhost:8010";
    private String userSystemUrl = "http://localhost:8006";
}
