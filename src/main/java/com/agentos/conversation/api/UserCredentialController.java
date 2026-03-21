package com.agentos.conversation.api;

import com.agentos.common.iam.IamActions;
import com.agentos.common.iam.ResourceArn;
import com.agentos.common.reactive.ReactiveSecurityContext;
import com.agentos.conversation.integration.UserSystemClient;
import com.agentos.conversation.security.IamPep;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * User personal credential management.
 * Proxies to User System which enforces USER ownership — Conversation never calls Credential Store directly.
 */
@RestController
@RequestMapping("/api/v1/me/credentials")
@RequiredArgsConstructor
public class UserCredentialController {

    private final UserSystemClient userSystemClient;
    private final IamPep iamPep;

    @PutMapping("/{scope}/{key}")
    public Mono<ResponseEntity<Map<String, Object>>> storeCredential(
            @PathVariable String scope,
            @PathVariable String key,
            @RequestBody Map<String, Object> body) {
        return Mono.zip(ReactiveSecurityContext.currentTenantIdAsUUID(),
                        ReactiveSecurityContext.currentUserIdAsUUID())
                .flatMap(tuple -> iamPep.require(tuple.getT1(), tuple.getT2(),
                                IamActions.USER_SYSTEM_USER_WRITE,
                                ResourceArn.userSystemUser(tuple.getT1(), tuple.getT2()))
                        .then(userSystemClient.putUserCredential(tuple.getT1(), tuple.getT2(), scope, key, body)))
                .map(ResponseEntity::ok);
    }

    @GetMapping
    public Mono<ResponseEntity<List<Map<String, Object>>>> listCredentials() {
        return Mono.zip(ReactiveSecurityContext.currentTenantIdAsUUID(),
                        ReactiveSecurityContext.currentUserIdAsUUID())
                .flatMap(tuple -> iamPep.require(tuple.getT1(), tuple.getT2(),
                                IamActions.USER_SYSTEM_USER_READ,
                                ResourceArn.userSystemUser(tuple.getT1(), tuple.getT2()))
                        .then(userSystemClient.listUserCredentials(tuple.getT1(), tuple.getT2())))
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{scope}/{key}")
    public Mono<ResponseEntity<Void>> deleteCredential(
            @PathVariable String scope,
            @PathVariable String key) {
        return Mono.zip(ReactiveSecurityContext.currentTenantIdAsUUID(),
                        ReactiveSecurityContext.currentUserIdAsUUID())
                .flatMap(tuple -> iamPep.require(tuple.getT1(), tuple.getT2(),
                                IamActions.USER_SYSTEM_USER_WRITE,
                                ResourceArn.userSystemUser(tuple.getT1(), tuple.getT2()))
                        .then(userSystemClient.deleteUserCredential(tuple.getT1(), tuple.getT2(), scope, key)))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
