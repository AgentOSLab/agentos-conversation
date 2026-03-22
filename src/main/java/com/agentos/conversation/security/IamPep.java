package com.agentos.conversation.security;

import com.agentos.common.iam.UserSystemPdpClient;
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

    private final UserSystemPdpClient userSystemPdpClient;

    public Mono<Void> require(UUID tenantId, UUID userId, String action, String resourceArn) {
        return userSystemPdpClient.requireAuthorization(tenantId, userId, action, resourceArn);
    }
}
