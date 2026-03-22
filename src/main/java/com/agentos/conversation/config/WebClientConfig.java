package com.agentos.conversation.config;

import com.agentos.common.alert.AlertClient;
import com.agentos.common.alert.DependencyAlertFilter;
import com.agentos.common.iam.UserSystemPdpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient bean definitions for downstream service integration.
 *
 * NOTE (ADR-046): Conversation does NOT have a RAG WebClient.
 * RAG retrieval happens inside Agent Runtime's tool execution layer (RagRetrievalTool),
 * not in Conversation's context pre-assembly. The former {@code ragWebClient} bean has
 * been removed to enforce this boundary at the wiring level.
 *
 * <p>Service token filter is injected per {@code @Bean} method as
 * {@code @Qualifier("serviceTokenExchangeFilter") ExchangeFilterFunction} (not the concrete
 * filter type on a field) to avoid Spring generic-resolution issues with nested BOOT-INF/lib jars
 * — same pattern as {@code agentos-audit} / {@code agentos-hub} WebClientConfig.
 */
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private static final String SERVICE_NAME = "conversation";

    private final ServiceUrlProperties serviceUrls;

    @Autowired(required = false)
    private AlertClient alertClient;

    @Bean
    public WebClient agentRuntimeWebClient(
            WebClient.Builder builder,
            @Autowired(required = false) @Qualifier("serviceTokenExchangeFilter") ExchangeFilterFunction serviceTokenFilter) {
        return withFilters(builder, serviceTokenFilter).baseUrl(serviceUrls.getAgentRuntimeUrl()).build();
    }

    @Bean
    public WebClient workflowRuntimeWebClient(
            WebClient.Builder builder,
            @Autowired(required = false) @Qualifier("serviceTokenExchangeFilter") ExchangeFilterFunction serviceTokenFilter) {
        return withFilters(builder, serviceTokenFilter).baseUrl(serviceUrls.getWorkflowRuntimeUrl()).build();
    }

    @Bean
    public WebClient sandboxWebClient(
            WebClient.Builder builder,
            @Autowired(required = false) @Qualifier("serviceTokenExchangeFilter") ExchangeFilterFunction serviceTokenFilter) {
        return withFilters(builder, serviceTokenFilter).baseUrl(serviceUrls.getSandboxUrl()).build();
    }

    @Bean
    public WebClient hubWebClient(
            WebClient.Builder builder,
            @Autowired(required = false) @Qualifier("serviceTokenExchangeFilter") ExchangeFilterFunction serviceTokenFilter) {
        return withFilters(builder, serviceTokenFilter).baseUrl(serviceUrls.getHubUrl()).build();
    }

    @Bean
    public WebClient llmGatewayWebClient(
            WebClient.Builder builder,
            @Autowired(required = false) @Qualifier("serviceTokenExchangeFilter") ExchangeFilterFunction serviceTokenFilter) {
        return withFilters(builder, serviceTokenFilter).baseUrl(serviceUrls.getLlmGatewayUrl()).build();
    }

    @Bean
    public WebClient userSystemWebClient(
            WebClient.Builder builder,
            @Autowired(required = false) @Qualifier("serviceTokenExchangeFilter") ExchangeFilterFunction serviceTokenFilter) {
        return withFilters(builder, serviceTokenFilter).baseUrl(serviceUrls.getUserSystemUrl()).build();
    }

    @Bean
    public UserSystemPdpClient userSystemPdpClient(@Qualifier("userSystemWebClient") WebClient userSystemWebClient,
                                                   @Value("${iam.fail-open:false}") boolean failOpen) {
        return new UserSystemPdpClient(userSystemWebClient, failOpen, UserSystemPdpClient.SubjectRolesForwarding.ENABLED);
    }

    private WebClient.Builder withFilters(WebClient.Builder builder, ExchangeFilterFunction serviceTokenFilter) {
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
