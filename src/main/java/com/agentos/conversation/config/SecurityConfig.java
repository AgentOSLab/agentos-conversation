package com.agentos.conversation.config;

import com.agentos.common.reactive.ReactiveJwtAuthFilter;
import com.agentos.common.reactive.ReactiveSecurityContext;
import com.agentos.common.reactive.ReactiveTenantJwtCompositeWebFilter;
import com.agentos.common.reactive.ReactiveTenantContextFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.WebFilter;

import java.util.Collections;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/actuator/health",
            "/actuator/info"
    };

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            @Value("${agentos.security.jwt.secret:}") String secret,
            @Value("${agentos.security.jwt.public-key-pem:}") String publicKeyPem,
            @Value("${agentos.security.jwt.trust-gateway:true}") boolean trustGateway,
            ObjectMapper objectMapper) {

        ReactiveJwtAuthFilter jwtFilter = createJwtFilter(secret, publicKeyPem, trustGateway, objectMapper);

        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        .anyExchange().authenticated()
                );

        // Stateless filter — use `new` so @WebFluxTest slices do not require ReactiveWebAutoConfiguration.
        http.addFilterAt(ReactiveTenantJwtCompositeWebFilter.of(new ReactiveTenantContextFilter(), jwtFilter),
                SecurityWebFiltersOrder.FIRST);
        http.addFilterAt(securityContextBridgeFilter(), SecurityWebFiltersOrder.AUTHENTICATION);

        return http.build();
    }

    private ReactiveJwtAuthFilter createJwtFilter(String secret, String publicKeyPem,
                                                   boolean trustGateway, ObjectMapper objectMapper) {
        boolean hasSecret = secret != null && !secret.isBlank();
        boolean hasPublicKey = publicKeyPem != null && !publicKeyPem.isBlank();
        if (!hasSecret && !hasPublicKey) return null;
        return new ReactiveJwtAuthFilter(
                hasSecret ? secret : null,
                hasPublicKey ? publicKeyPem : null,
                List.of(PUBLIC_PATHS),
                objectMapper,
                trustGateway
        );
    }

    @SuppressWarnings("unchecked")
    private WebFilter securityContextBridgeFilter() {
        return (exchange, chain) -> {
            String userId = exchange.getAttribute(ReactiveSecurityContext.USER_ID_ATTR);
            if (userId != null) {
                List<String> roles = exchange.getAttribute(ReactiveSecurityContext.ROLES_ATTR);
                var authorities = roles != null
                        ? roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()
                        : Collections.<SimpleGrantedAuthority>emptyList();
                var auth = new UsernamePasswordAuthenticationToken(userId, "", authorities);
                return chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
            }
            return chain.filter(exchange);
        };
    }
}
