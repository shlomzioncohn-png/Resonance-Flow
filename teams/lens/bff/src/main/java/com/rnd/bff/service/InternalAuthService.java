package com.rnd.bff.service;

import com.rnd.bff.client.InboxAuthUserResponse;
import com.rnd.bff.client.InboxUsersClient;
import com.rnd.bff.dto.AuthUserResponseDto;
import com.rnd.bff.dto.AuthVerifyRequestDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for internal authentication endpoints in BFF.
 * Orchestrates communication with Inbox auth endpoints and maps roles.
 */
@Service
public class InternalAuthService {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthService.class);

    private final InboxUsersClient inboxUsersClient;
    private final RoleMapper roleMapper;

    public InternalAuthService(InboxUsersClient inboxUsersClient, RoleMapper roleMapper) {
        this.inboxUsersClient = inboxUsersClient;
        this.roleMapper = roleMapper;
    }

    /**
     * Verify user credentials by forwarding to Inbox and mapping the role.
     * 
     * @param request containing login (email or username) and password
     * @return user info with role mapped to LENS role
     */
    public AuthUserResponseDto verify(AuthVerifyRequestDto request) {
        log.debug("Verifying credentials for: {}", request.getLogin());

        InboxAuthUserResponse inboxResponse = inboxUsersClient.verify(
                request.getLogin(),
                request.getPassword()
        );

        // Map Inbox role to LENS role
        String lensRole = roleMapper.mapToLensRole(inboxResponse.getRole());

        AuthUserResponseDto response = new AuthUserResponseDto(
                inboxResponse.getId(),
                inboxResponse.getDisplayName(),
                lensRole
        );

        log.info("User verified: {} with role {}", inboxResponse.getId(), lensRole);
        return response;
    }
}
