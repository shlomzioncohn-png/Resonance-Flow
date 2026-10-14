package com.rnd.bff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bff.client.InboxUserClient;
import com.rnd.bff.client.InboxUserPageResponse;
import com.rnd.bff.client.InboxUserRecord;
import com.rnd.bff.dto.UserDetailsDTO;
import com.rnd.bff.dto.UserListResponseDTO;
import com.rnd.bff.exception.InboxResponseException;
import com.rnd.bff.exception.SelfStatusChangeException;
import com.rnd.bff.exception.ServiceUnavailableException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Unit tests for ManagerUsersService — task spec manager-users-screen-task.md
 * §4 (business rules) and §5 (BFF behavior: allowlist, partial PATCH, error
 * mapping). InboxUserClient is mocked; these don't touch a real Inbox.
 */
@ExtendWith(MockitoExtension.class)
class ManagerUsersServiceTest {

    @Mock private InboxUserClient inboxUserClient;

    private ManagerUsersService managerUsersService;

    private static final String ACTOR_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_USER_ID = "22222222-2222-2222-2222-222222222222";

    @BeforeEach
    void setUp() {
        managerUsersService = new ManagerUsersService(inboxUserClient, new ObjectMapper(), "");
    }

    private static InboxUserRecord record(String id, String role) {
        InboxUserRecord r = new InboxUserRecord();
        r.setId(id);
        r.setDisplayName("Some User");
        r.setEmail("user@example.com");
        r.setUsername("someuser");
        r.setRole(role);
        r.setStatus("ACTIVE");
        return r;
    }

    // -------------------------------------------------------------------------
    // listUsers — role mapping, tenant never exposed
    // -------------------------------------------------------------------------

    @Test
    void listUsers_mapsInboxRoleToLensRoleAndExcludesTenant() {
        InboxUserRecord manager = record(ACTOR_ID, "SYSTEM_ADMIN");
        InboxUserRecord employee = record(OTHER_USER_ID, "TEAM_LEAD");
        InboxUserPageResponse page = new InboxUserPageResponse(List.of(manager, employee), 0, 20, 2, 1);
        when(inboxUserClient.listUsers(ACTOR_ID, 0, 20, null)).thenReturn(page);

        UserListResponseDTO result = managerUsersService.listUsers(ACTOR_ID, 0, 20, null);

        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems().get(0).getRole()).isEqualTo("MANAGER"); // SYSTEM_ADMIN -> MANAGER
        assertThat(result.getItems().get(1).getRole()).isEqualTo("EMPLOYEE"); // TEAM_LEAD -> EMPLOYEE
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void listUsers_flagsOnlyTheActorsOwnRowAsCurrentUser() {
        InboxUserPageResponse page = new InboxUserPageResponse(
                List.of(record(ACTOR_ID, "SYSTEM_ADMIN"), record(OTHER_USER_ID, "TEAM_LEAD")), 0, 20, 2, 1);
        when(inboxUserClient.listUsers(ACTOR_ID, 0, 20, null)).thenReturn(page);

        UserListResponseDTO result = managerUsersService.listUsers(ACTOR_ID, 0, 20, null);

        assertThat(result.getItems().get(0).isCurrentUser()).isTrue();
        assertThat(result.getItems().get(1).isCurrentUser()).isFalse();
    }

    // -------------------------------------------------------------------------
    // updateUser — allowlist is enforced here too, not just trusted from Gateway
    // -------------------------------------------------------------------------

    @Test
    void updateUser_dropsFieldsOutsideTheAllowlistBeforeCallingInbox() {
        when(inboxUserClient.updateUser(eq(ACTOR_ID), eq(OTHER_USER_ID), any()))
                .thenReturn(record(OTHER_USER_ID, "EMPLOYEE"));

        Map<String, Object> incoming = Map.of(
                "phone", "555-1234",
                "role", "SYSTEM_ADMIN",
                "email", "hacked@evil.com",
                "username", "newname");

        managerUsersService.updateUser(ACTOR_ID, OTHER_USER_ID, incoming);

        verify(inboxUserClient).updateUser(eq(ACTOR_ID), eq(OTHER_USER_ID), argThat(forwarded ->
                forwarded.size() == 1 && "555-1234".equals(forwarded.get("phone"))));
    }

    @Test
    void updateUser_onlyForwardsFieldsActuallyPresent_doesNotInventNulls() {
        when(inboxUserClient.updateUser(eq(ACTOR_ID), eq(OTHER_USER_ID), any()))
                .thenReturn(record(OTHER_USER_ID, "EMPLOYEE"));

        managerUsersService.updateUser(ACTOR_ID, OTHER_USER_ID, Map.of("firstName", "Alex"));

        verify(inboxUserClient).updateUser(eq(ACTOR_ID), eq(OTHER_USER_ID), argThat(forwarded ->
                forwarded.size() == 1 && forwarded.containsKey("firstName")
                        && !forwarded.containsKey("lastName") && !forwarded.containsKey("phone")
                        && !forwarded.containsKey("displayName")));
    }

    // -------------------------------------------------------------------------
    // updateStatus — business rules
    // -------------------------------------------------------------------------

    @Test
    void updateStatus_targetingSelf_throwsWithoutCallingInbox() {
        assertThatThrownBy(() -> managerUsersService.updateStatus(ACTOR_ID, ACTOR_ID, "SUSPENDED"))
                .isInstanceOf(SelfStatusChangeException.class);

        verify(inboxUserClient, never()).updateStatus(anyString(), anyString(), anyString());
    }

    @Test
    void updateStatus_pendingIsNotAnAllowedTransitionFromThisScreen() {
        assertThatThrownBy(() -> managerUsersService.updateStatus(ACTOR_ID, OTHER_USER_ID, "PENDING"))
                .isInstanceOf(InboxResponseException.class)
                .satisfies(e -> assertThat(((InboxResponseException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(inboxUserClient, never()).updateStatus(anyString(), anyString(), anyString());
    }

    @Test
    void updateStatus_unknownValue_rejectedBeforeReachingInbox() {
        assertThatThrownBy(() -> managerUsersService.updateStatus(ACTOR_ID, OTHER_USER_ID, "NOT_A_REAL_STATUS"))
                .isInstanceOf(InboxResponseException.class);

        verify(inboxUserClient, never()).updateStatus(anyString(), anyString(), anyString());
    }

    @Test
    void updateStatus_differentUser_delegatesToInbox() {
        when(inboxUserClient.updateStatus(ACTOR_ID, OTHER_USER_ID, "SUSPENDED"))
                .thenReturn(record(OTHER_USER_ID, "EMPLOYEE"));

        UserDetailsDTO result = managerUsersService.updateStatus(ACTOR_ID, OTHER_USER_ID, "SUSPENDED");

        assertThat(result.getId()).isEqualTo(OTHER_USER_ID);
        verify(inboxUserClient).updateStatus(ACTOR_ID, OTHER_USER_ID, "SUSPENDED");
    }

    @Test
    void devActorId_replacesTheGatewaysMockUserId() {
        ManagerUsersService withDevActor = new ManagerUsersService(inboxUserClient, new ObjectMapper(), ACTOR_ID);

        assertThatThrownBy(() -> withDevActor.updateStatus("mgr_001", ACTOR_ID, "SUSPENDED"))
                .isInstanceOf(SelfStatusChangeException.class);

        when(inboxUserClient.getUser(ACTOR_ID, ACTOR_ID)).thenReturn(record(ACTOR_ID, "SYSTEM_ADMIN"));
        assertThat(withDevActor.getUser("mgr_001", ACTOR_ID).isCurrentUser()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Error mapping — task spec §5
    // -------------------------------------------------------------------------

    @Test
    void getUser_inboxReturns404_translatedToInboxResponseExceptionWith404() {
        when(inboxUserClient.getUser(ACTOR_ID, OTHER_USER_ID)).thenThrow(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
                        "{\"message\":\"User not found\"}".getBytes(), null));

        assertThatThrownBy(() -> managerUsersService.getUser(ACTOR_ID, OTHER_USER_ID))
                .isInstanceOf(InboxResponseException.class)
                .satisfies(e -> {
                    assertThat(((InboxResponseException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("User not found");
                });
    }

    @Test
    void getUser_inboxUnreachable_translatedToServiceUnavailable() {
        when(inboxUserClient.getUser(ACTOR_ID, OTHER_USER_ID))
                .thenThrow(new ResourceAccessException("connect failed", new SocketTimeoutException()));

        assertThatThrownBy(() -> managerUsersService.getUser(ACTOR_ID, OTHER_USER_ID))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void getUser_inboxReturns500_translatedToServiceUnavailableNotPassedThrough() {
        when(inboxUserClient.getUser(ACTOR_ID, OTHER_USER_ID)).thenThrow(
                HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                        HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> managerUsersService.getUser(ACTOR_ID, OTHER_USER_ID))
                .isInstanceOf(ServiceUnavailableException.class);
    }
}
