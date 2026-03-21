package com.agentos.conversation.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class UserSystemIamClient {

    private final WebClient webClient;

    @Value("${iam.fail-open:false}")
    private boolean failOpen;

    public UserSystemIamClient(@Qualifier("userSystemWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<Void> requireAuthorization(UUID tenantId, UUID userId, String action, String resourceArn) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("principal", Map.of("type", "user", "id", userId.toString()));
        body.put("action", action);
        body.put("resourceArn", resourceArn);

        return webClient.post()
                .uri("/api/v1/authorization/check")
                .header("X-Tenant-Id", tenantId.toString())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(json -> Boolean.TRUE.equals(json.get("allowed")))
                .doOnError(e -> log.warn("IAM check failed (defaulting to {}): {}", failOpen ? "allow" : "deny", e.getMessage()))
                .onErrorReturn(failOpen)
                .flatMap(allowed -> {
                    if (!Boolean.TRUE.equals(allowed)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "Not authorized to perform: " + action));
                    }
                    return Mono.empty();
                });
    }
}
