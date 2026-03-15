package com.agentos.conversation.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final ServiceUrlProperties serviceUrls;

    @Bean
    public WebClient agentRuntimeWebClient(WebClient.Builder builder) {
        return builder.baseUrl(serviceUrls.getAgentRuntimeUrl()).build();
    }

    @Bean
    public WebClient hubWebClient(WebClient.Builder builder) {
        return builder.baseUrl(serviceUrls.getHubUrl()).build();
    }

    @Bean
    public WebClient llmGatewayWebClient(WebClient.Builder builder) {
        return builder.baseUrl(serviceUrls.getLlmGatewayUrl()).build();
    }

    @Bean
    public WebClient mcpRuntimeWebClient(WebClient.Builder builder) {
        return builder.baseUrl(serviceUrls.getMcpRuntimeUrl()).build();
    }

    @Bean
    public WebClient ragWebClient(WebClient.Builder builder) {
        return builder.baseUrl(serviceUrls.getRagUrl()).build();
    }

    @Bean
    public WebClient userSystemWebClient(WebClient.Builder builder) {
        return builder.baseUrl(serviceUrls.getUserSystemUrl()).build();
    }
}
