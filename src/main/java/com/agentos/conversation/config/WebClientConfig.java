package com.agentos.conversation.config;

import com.agentos.common.alert.AlertClient;
import com.agentos.common.alert.DependencyAlertFilter;
import com.agentos.common.auth.ServiceTokenExchangeFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient bean definitions for downstream service integration.
 *
 * NOTE (ADR-046): Conversation does NOT have a RAG WebClient.
 * RAG retrieval happens inside Agent Runtime's tool execution layer (RagRetrievalTool),
 * not in Conversation's context pre-assembly. The former {@code ragWebClient} bean has
 * been removed to enforce this boundary at the wiring level.
 */
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private static final String SERVICE_NAME = "conversation";

    private final ServiceUrlProperties serviceUrls;

    @Autowired(required = false)
    private ServiceTokenExchangeFilter serviceTokenFilter;

    @Autowired(required = false)
    private AlertClient alertClient;

    @Bean
    public WebClient agentRuntimeWebClient(WebClient.Builder builder) {
        return withFilters(builder).baseUrl(serviceUrls.getAgentRuntimeUrl()).build();
    }

    @Bean
    public WebClient hubWebClient(WebClient.Builder builder) {
        return withFilters(builder).baseUrl(serviceUrls.getHubUrl()).build();
    }

    @Bean
    public WebClient llmGatewayWebClient(WebClient.Builder builder) {
        return withFilters(builder).baseUrl(serviceUrls.getLlmGatewayUrl()).build();
    }

    @Bean
    public WebClient userSystemWebClient(WebClient.Builder builder) {
        return withFilters(builder).baseUrl(serviceUrls.getUserSystemUrl()).build();
    }

    private WebClient.Builder withFilters(WebClient.Builder builder) {
        WebClient.Builder clone = builder.clone();
        if (serviceTokenFilter != null) {
            clone.filter(serviceTokenFilter);
        }
        if (alertClient != null) {
            clone.filter(new DependencyAlertFilter(alertClient, SERVICE_NAME));
        }
        return clone;
    }
}
