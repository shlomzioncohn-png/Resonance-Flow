package com.rnd.inbox.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.config.AppConfig;
import com.rnd.inbox.config.WebMvcConfig;
import com.rnd.inbox.dto.OrganizationSummaryDto;
import com.rnd.inbox.dto.PageResponseDto;
import com.rnd.inbox.dto.UserCreateRequestDto;
import com.rnd.inbox.dto.UserResponseDto;
import com.rnd.inbox.dto.UserStatusUpdateRequestDto;
import com.rnd.inbox.dto.UserUpdateRequestDto;
import com.rnd.inbox.exception.DuplicateEmailException;
import com.rnd.inbox.exception.OrganizationNotFoundException;
import com.rnd.inbox.exception.UserNotFoundException;
import com.rnd.inbox.model.enums.UserStatus;
import com.rnd.inbox.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({AppConfig.class, WebMvcConfig.class})
class UserControllerTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    private UUID userId;
    private UUID organizationId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        organizationId = UUID.randomUUID();
        actorId = UUID.randomUUID();
    }

    private UserResponseDto sampleUser() {
        return new UserResponseDto(userId, "Yehudit Cohen", null, null, "yehudit@acme.com",
                null, "yehudit", "developer", UserStatus.ACTIVE,
                new OrganizationSummaryDto(organizationId, "Acme Inc", "acme"), null, null, null);
    }

    private UserCreateRequestDto validCreateRequest() {
        UserCreateRequestDto request = new UserCreateRequestDto();
        request.setOrganizationId(organizationId.toString());
        request.setDisplayName("Yehudit Cohen");
        request.setRole("developer");
        return request;
    }

    private UserUpdateRequestDto validUpdateRequest() {
        UserUpdateRequestDto request = new UserUpdateRequestDto();
        request.setDisplayName("Yehudit Cohen");
        request.setRole("developer");
        return request;
    }

    @Test
    void createUser_withValidRequest_returns201WithLocationHeader() throws Exception {
        when(userService.createUser(any(), eq(actorId))).thenReturn(sampleUser());

        mockMvc.perform(post("/api/v1/users")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/users/" + userId)))
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.displayName").value("Yehudit Cohen"))
                .andExpect(jsonPath("$.organization.name").value("Acme Inc"));
    }

    @Test
    void createUser_withMissingRequiredFields_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UserCreateRequestDto())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void createUser_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void createUser_whenOrganizationNotFound_returns404() throws Exception {
        when(userService.createUser(any(), eq(actorId))).thenThrow(new OrganizationNotFoundException(organizationId));

        mockMvc.perform(post("/api/v1/users")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORGANIZATION_NOT_FOUND"));
    }

    @Test
    void createUser_whenEmailAlreadyExists_returns409() throws Exception {
        when(userService.createUser(any(), eq(actorId))).thenThrow(new DuplicateEmailException("yehudit@acme.com"));

        mockMvc.perform(post("/api/v1/users")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_EMAIL"));
    }

    @Test
    void getUserById_whenFound_returns200() throws Exception {
        when(userService.getUserById(userId)).thenReturn(sampleUser());

        mockMvc.perform(get("/api/v1/users/{id}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.organization.name").value("Acme Inc"));
    }

    @Test
    void getUserById_whenNotFound_returns404() throws Exception {
        when(userService.getUserById(userId)).thenThrow(new UserNotFoundException(userId));

        mockMvc.perform(get("/api/v1/users/{id}", userId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    @Test
    void listUsers_returns200WithPageResponse() throws Exception {
        PageResponseDto<UserResponseDto> page = new PageResponseDto<>(List.of(sampleUser()), 0, 20, 1, 1);
        when(userService.listUsers(any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(userId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void updateUser_withValidRequest_returns200() throws Exception {
        when(userService.updateUser(eq(userId), any(), eq(actorId))).thenReturn(sampleUser());

        mockMvc.perform(put("/api/v1/users/{id}", userId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()));
    }

    @Test
    void updateUser_withInvalidEmail_returns400() throws Exception {
        UserUpdateRequestDto request = validUpdateRequest();
        request.setEmail("not-an-email");

        mockMvc.perform(put("/api/v1/users/{id}", userId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void updateUser_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/users/{id}", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void updateUser_whenNotFound_returns404() throws Exception {
        when(userService.updateUser(eq(userId), any(), eq(actorId))).thenThrow(new UserNotFoundException(userId));

        mockMvc.perform(put("/api/v1/users/{id}", userId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    @Test
    void updateStatus_withValidStatus_returns200() throws Exception {
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");
        when(userService.updateStatus(eq(userId), any())).thenReturn(sampleUser());

        mockMvc.perform(patch("/api/v1/users/{id}/status", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()));
    }

    @Test
    void updateStatus_withInvalidStatusValue_returns400() throws Exception {
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("NOT_A_REAL_STATUS");

        mockMvc.perform(patch("/api/v1/users/{id}/status", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void updateStatus_whenNotFound_returns404() throws Exception {
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");
        when(userService.updateStatus(eq(userId), any())).thenThrow(new UserNotFoundException(userId));

        mockMvc.perform(patch("/api/v1/users/{id}/status", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }
}
