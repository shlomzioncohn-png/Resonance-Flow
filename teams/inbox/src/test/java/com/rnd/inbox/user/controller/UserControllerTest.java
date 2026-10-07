package com.rnd.inbox.user.controller;
import com.rnd.inbox.user.dto.UserCreateRequestDto;
import com.rnd.inbox.user.dto.UserResponseDto;
import com.rnd.inbox.user.dto.UserStatusUpdateRequestDto;
import com.rnd.inbox.user.dto.UserUpdateRequestDto;
import com.rnd.inbox.user.exception.DuplicateEmailException;
import com.rnd.inbox.user.exception.UserNotFoundException;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.model.UserStatus;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.shared.config.AppConfig;
import com.rnd.inbox.shared.config.WebMvcConfig;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.tenant.exception.TenantNotFoundException;
import com.rnd.inbox.tenant.dto.TenantSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @MockBean
    private UserRepository userRepository;

    private UUID userId;
    private UUID tenantId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        actorId = UUID.randomUUID();

        // @RequireRole (createUser) looks the actor up directly via UserRepository, independently
        // of whatever UserService is stubbed to do — default it to a permitted role (TENANT_ADMIN)
        // so tests unrelated to the coarse role gate aren't blocked by it. Endpoints without
        // @RequireRole never touch this stub at all.
        User actorEntity = new User();
        actorEntity.setId(actorId);
        actorEntity.setRole(UserRole.TENANT_ADMIN);
        actorEntity.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actorEntity));
    }

    private UserResponseDto sampleUser() {
        return new UserResponseDto(userId, "Yehudit Cohen", null, null, "yehudit@acme.com",
                null, "yehudit", UserRole.EMPLOYEE, UserStatus.ACTIVE,
                new TenantSummaryDto(tenantId, "Acme Inc", "acme"), null, null, null);
    }

    private UserCreateRequestDto validCreateRequest() {
        UserCreateRequestDto request = new UserCreateRequestDto();
        request.setTenantId(tenantId.toString());
        request.setDisplayName("Yehudit Cohen");
        request.setRole("EMPLOYEE");
        return request;
    }

    private UserUpdateRequestDto validUpdateRequest() {
        UserUpdateRequestDto request = new UserUpdateRequestDto();
        request.setDisplayName("Yehudit Cohen");
        request.setRole("EMPLOYEE");
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
                .andExpect(jsonPath("$.display_name").value("Yehudit Cohen"))
                .andExpect(jsonPath("$.tenant.name").value("Acme Inc"));
    }

    @Test
    void createUser_withMissingRequiredFields_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UserCreateRequestDto())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.field_errors").isArray());
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
    void createUser_whenTenantNotFound_returns404() throws Exception {
        when(userService.createUser(any(), eq(actorId))).thenThrow(new TenantNotFoundException(tenantId));

        mockMvc.perform(post("/api/v1/users")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_FOUND"));
    }

    @Test
    void createUser_whenActorOutsideRequestedTenant_returns403() throws Exception {
        when(userService.createUser(any(), eq(actorId)))
                .thenThrow(new ForbiddenActionException("Actor cannot act on tenant " + tenantId));

        mockMvc.perform(post("/api/v1/users")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void createUser_whenActorRoleIsTeamLead_returns403WithoutCallingService() throws Exception {
        User actorEntity = new User();
        actorEntity.setId(actorId);
        actorEntity.setRole(UserRole.TEAM_LEAD);
        actorEntity.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actorEntity));

        mockMvc.perform(post("/api/v1/users")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(userService, never()).createUser(any(), any());
    }

    @Test
    void createUser_whenActorRoleIsEmployee_returns403WithoutCallingService() throws Exception {
        User actorEntity = new User();
        actorEntity.setId(actorId);
        actorEntity.setRole(UserRole.EMPLOYEE);
        actorEntity.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actorEntity));

        mockMvc.perform(post("/api/v1/users")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(userService, never()).createUser(any(), any());
    }

    @Test
    void createUser_whenActorIdUnknownToRepository_returns401() throws Exception {
        when(userRepository.findById(actorId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/users")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));

        verify(userService, never()).createUser(any(), any());
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
        when(userService.getUserById(userId, actorId)).thenReturn(sampleUser());

        mockMvc.perform(get("/api/v1/users/{id}", userId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.tenant.name").value("Acme Inc"));
    }

    @Test
    void getUserById_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}", userId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void getUserById_whenNotFound_returns404() throws Exception {
        when(userService.getUserById(userId, actorId)).thenThrow(new UserNotFoundException(userId));

        mockMvc.perform(get("/api/v1/users/{id}", userId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    @Test
    void getUserById_whenActorOutsideTargetTenant_returns403() throws Exception {
        when(userService.getUserById(userId, actorId))
                .thenThrow(new ForbiddenActionException("Actor cannot act on tenant " + tenantId));

        mockMvc.perform(get("/api/v1/users/{id}", userId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void listUsers_returns200WithPageResponse() throws Exception {
        PageResponseDto<UserResponseDto> page = new PageResponseDto<>(List.of(sampleUser()), 0, 20, 1, 1);
        when(userService.listUsers(any(), eq(actorId))).thenReturn(page);

        mockMvc.perform(get("/api/v1/users").header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(userId.toString()))
                .andExpect(jsonPath("$.total_elements").value(1))
                .andExpect(jsonPath("$.total_pages").value(1));
    }

    @Test
    void listUsers_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void updateUser_withValidRequest_returns200() throws Exception {
        when(userService.updateUser(eq(userId), any(), eq(actorId))).thenReturn(sampleUser());

        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()));
    }

    @Test
    void updateUser_withOnlyOneFieldProvided_returns200() throws Exception {
        when(userService.updateUser(eq(userId), any(), eq(actorId))).thenReturn(sampleUser());
        UserUpdateRequestDto request = new UserUpdateRequestDto();
        request.setEmail(JsonNullable.of("only-this-changes@acme.com"));

        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void updateUser_withInvalidEmail_returns400() throws Exception {
        UserUpdateRequestDto request = validUpdateRequest();
        request.setEmail(JsonNullable.of("not-an-email"));

        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void updateUser_withBlankDisplayName_returns400() throws Exception {
        UserUpdateRequestDto request = validUpdateRequest();
        request.setDisplayName("   ");

        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void updateUser_withBlankRole_returns400() throws Exception {
        UserUpdateRequestDto request = validUpdateRequest();
        request.setRole("");

        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void updateUser_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void updateUser_whenNotFound_returns404() throws Exception {
        when(userService.updateUser(eq(userId), any(), eq(actorId))).thenThrow(new UserNotFoundException(userId));

        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    @Test
    void updateUser_whenActorOutsideTargetTenant_returns403() throws Exception {
        when(userService.updateUser(eq(userId), any(), eq(actorId)))
                .thenThrow(new ForbiddenActionException("Actor cannot manage users outside their own tenant"));

        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void updateStatus_withValidStatus_returns200() throws Exception {
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");
        when(userService.updateStatus(eq(userId), any(), eq(actorId))).thenReturn(sampleUser());

        mockMvc.perform(patch("/api/v1/users/{id}/status", userId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()));
    }

    @Test
    void updateStatus_withoutActorHeader_returns401() throws Exception {
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");

        mockMvc.perform(patch("/api/v1/users/{id}/status", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
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
        when(userService.updateStatus(eq(userId), any(), eq(actorId))).thenThrow(new UserNotFoundException(userId));

        mockMvc.perform(patch("/api/v1/users/{id}/status", userId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    @Test
    void updateStatus_whenActorOutsideTargetTenant_returns403() throws Exception {
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");
        when(userService.updateStatus(eq(userId), any(), eq(actorId)))
                .thenThrow(new ForbiddenActionException("Actor cannot manage users outside their own tenant"));

        mockMvc.perform(patch("/api/v1/users/{id}/status", userId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }
}
