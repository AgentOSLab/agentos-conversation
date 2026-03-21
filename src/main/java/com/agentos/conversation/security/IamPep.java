package com.agentos.conversation.security;

import com.agentos.conversation.integration.UserSystemIamClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * PDP-backed PEP for conversation HTTP controllers — delegates to user-system {@code /authorization/check}.
 */
@Component
@RequiredArgsConstructor
public class IamPep {

    private final UserSystemIamClient userSystemIamClient;

    public Mono<Void> require(UUID tenantId, UUID userId, String action, String resourceArn) {
        return userSystemIamClient.requireAuthorization(tenantId, userId, action, resourceArn);
    }
}
