package com.rnd.bff.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bff.config.SecurityConfig;
import com.rnd.bff.dto.UserCreateRequestDTO;
import com.rnd.bff.dto.UserDetailsDTO;
import com.rnd.bff.dto.UserListResponseDTO;
import com.rnd.bff.dto.UserRowDTO;
import com.rnd.bff.dto.UserStatusUpdateRequestDTO;
import com.rnd.bff.exception.SelfStatusChangeException;
import com.rnd.bff.service.ManagerUsersService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-layer tests for ManagerUsersController — request/response cycles,
 * header handling, status codes. ManagerUsersService is mocked here (its own
 * behavior is covered by ManagerUsersServiceTest); this only checks that the
 * controller wires HTTP correctly to it.
 *
 * @Import(SecurityConfig.class) — without it, this WebMvcTest slice falls
 * back to Spring Security's default (deny-all) instead of BFF's own
 * permitAll, same trap WorkspaceControllerTest is currently caught in.
 */
@WebMvcTest(ManagerUsersController.class)
@Import(SecurityConfig.class)
class ManagerUsersControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private ManagerUsersService managerUsersService;

    private static final String ACTOR_ID = "11111111-1111-1111-1111-111111111111";
    private static final String TARGET_ID = "22222222-2222-2222-2222-222222222222";

    @Test
    void listUsers_forwardsActorIdAndPaginationToService() throws Exception {
        UserListResponseDTO response = UserListResponseDTO.builder()
                .items(List.of(UserRowDTO.builder().id(TARGET_ID).displayName("Eli").role("EMPLOYEE").status("ACTIVE").build()))
                .page(1).size(5).totalElements(1).totalPages(1)
                .build();
        when(managerUsersService.listUsers(ACTOR_ID, 1, 5, "displayName,asc")).thenReturn(response);

        mockMvc.perform(get("/manager/users?page=1&size=5&sort=displayName,asc")
                        .header("X-User-Id", ACTOR_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].displayName", is("Eli")))
                .andExpect(jsonPath("$.page", is(1)));
    }

    @Test
    void listUsers_missingActorIdHeader_returns400() throws Exception {
        mockMvc.perform(get("/manager/users"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getUser_returnsDetailsFromService() throws Exception {
        UserDetailsDTO details = UserDetailsDTO.detailsBuilder()
                .id(TARGET_ID).displayName("Eli").role("EMPLOYEE").status("ACTIVE").username("eli").build();
        when(managerUsersService.getUser(ACTOR_ID, TARGET_ID)).thenReturn(details);

        mockMvc.perform(get("/manager/users/" + TARGET_ID).header("X-User-Id", ACTOR_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", is("eli")));
    }

    @Test
    void createUser_returns201WithTheCreatedUser() throws Exception {
        UserDetailsDTO created = UserDetailsDTO.detailsBuilder()
                .id(TARGET_ID).displayName("Noa").role("EMPLOYEE").status("PENDING").build();
        when(managerUsersService.createUser(eq(ACTOR_ID), any(UserCreateRequestDTO.class))).thenReturn(created);

        mockMvc.perform(post("/manager/users")
                        .header("X-User-Id", ACTOR_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Noa\",\"role\":\"EMPLOYEE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(TARGET_ID)))
                .andExpect(jsonPath("$.status", is("PENDING")));

        verify(managerUsersService).createUser(eq(ACTOR_ID),
                eq(UserCreateRequestDTO.builder().displayName("Noa").role("EMPLOYEE").build()));
    }

    @Test
    void updateUser_passesRequestBodyThroughToService() throws Exception {
        UserDetailsDTO updated = UserDetailsDTO.detailsBuilder()
                .id(TARGET_ID).displayName("Eli").role("EMPLOYEE").status("ACTIVE").phone("555-1234").build();
        when(managerUsersService.updateUser(eq(ACTOR_ID), eq(TARGET_ID), anyMap())).thenReturn(updated);

        mockMvc.perform(patch("/manager/users/" + TARGET_ID)
                        .header("X-User-Id", ACTOR_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"555-1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone", is("555-1234")));

        verify(managerUsersService).updateUser(eq(ACTOR_ID), eq(TARGET_ID), eq(Map.of("phone", "555-1234")));
    }

    @Test
    void updateStatus_selfChange_returns409WithServiceMessage() throws Exception {
        when(managerUsersService.updateStatus(ACTOR_ID, ACTOR_ID, "SUSPENDED"))
                .thenThrow(new SelfStatusChangeException("A manager cannot change their own status."));

        UserStatusUpdateRequestDTO body = new UserStatusUpdateRequestDTO();
        body.setStatus("SUSPENDED");

        mockMvc.perform(patch("/manager/users/" + ACTOR_ID + "/status")
                        .header("X-User-Id", ACTOR_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("A manager cannot change their own status.")));
    }

    @Test
    void updateStatus_differentUser_returns200() throws Exception {
        UserDetailsDTO updated = UserDetailsDTO.detailsBuilder()
                .id(TARGET_ID).displayName("Eli").role("EMPLOYEE").status("SUSPENDED").build();
        when(managerUsersService.updateStatus(ACTOR_ID, TARGET_ID, "SUSPENDED")).thenReturn(updated);

        UserStatusUpdateRequestDTO body = new UserStatusUpdateRequestDTO();
        body.setStatus("SUSPENDED");

        mockMvc.perform(patch("/manager/users/" + TARGET_ID + "/status")
                        .header("X-User-Id", ACTOR_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUSPENDED")));
    }
}
