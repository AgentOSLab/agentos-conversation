package com.agentos.conversation.api;

import com.agentos.common.reactive.ReactiveSecurityContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * User personal credential management.
 * Users can store, list, and delete their own credentials (ownership_level=USER).
 * Values are proxied directly to Credential Store — Conversation never decrypts them.
 */
@RestController
@RequestMapping("/api/v1/me/credentials")
@RequiredArgsConstructor
public class UserCredentialController {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    @Qualifier("credentialStoreWebClient")
    private final WebClient credentialStoreClient;

    @PutMapping("/{scope}/{key}")
    public Mono<ResponseEntity<Map<String, Object>>> storeCredential(
            @PathVariable String scope,
            @PathVariable String key,
            @RequestBody Map<String, Object> body) {
        return Mono.zip(ReactiveSecurityContext.currentTenantIdAsUUID(),
                        ReactiveSecurityContext.currentUserIdAsUUID())
                .flatMap(tuple -> {
                    var tenantId = tuple.getT1();
                    var userId = tuple.getT2();
                    body.put("ownershipLevel", "USER");
                    body.put("ownerId", userId.toString());
                    return credentialStoreClient.put()
                            .uri("/api/v1/credentials/{scope}/{key}", scope, key)
                            .header("X-Tenant-Id", tenantId.toString())
                            .header("X-User-Id", userId.toString())
                            .header("X-Caller-Role", "user")
                            .header("X-Requested-By", "conversation:" + userId)
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(MAP_TYPE)
                            .map(ResponseEntity::ok);
                });
    }

    @GetMapping
    public Mono<ResponseEntity<List<Map<String, Object>>>> listCredentials() {
        return Mono.zip(ReactiveSecurityContext.currentTenantIdAsUUID(),
                        ReactiveSecurityContext.currentUserIdAsUUID())
                .flatMap(tuple -> {
                    var tenantId = tuple.getT1();
                    var userId = tuple.getT2();
                    return credentialStoreClient.get()
                            .uri(uriBuilder -> uriBuilder
                                    .path("/api/v1/credentials")
                                    .queryParam("ownershipLevel", "USER")
                                    .build())
                            .header("X-Tenant-Id", tenantId.toString())
                            .header("X-User-Id", userId.toString())
                            .header("X-Caller-Role", "user")
                            .retrieve()
                            .bodyToMono(LIST_TYPE)
                            .map(ResponseEntity::ok);
                });
    }

    @DeleteMapping("/{scope}/{key}")
    public Mono<ResponseEntity<Void>> deleteCredential(
            @PathVariable String scope,
            @PathVariable String key) {
        return Mono.zip(ReactiveSecurityContext.currentTenantIdAsUUID(),
                        ReactiveSecurityContext.currentUserIdAsUUID())
                .flatMap(tuple -> {
                    var tenantId = tuple.getT1();
                    var userId = tuple.getT2();
                    return credentialStoreClient.delete()
                            .uri(uriBuilder -> uriBuilder
                                    .path("/api/v1/credentials/{scope}/{key}")
                                    .queryParam("ownershipLevel", "USER")
                                    .queryParam("ownerId", userId.toString())
                                    .build(scope, key))
                            .header("X-Tenant-Id", tenantId.toString())
                            .header("X-User-Id", userId.toString())
                            .header("X-Caller-Role", "user")
                            .header("X-Requested-By", "conversation:" + userId)
                            .retrieve()
                            .toBodilessEntity()
                            .map(r -> ResponseEntity.noContent().<Void>build());
                });
    }
}
