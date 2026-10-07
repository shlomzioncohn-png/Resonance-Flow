package com.rnd.inbox.tenant.controller;
import com.rnd.inbox.tenant.dto.TenantCreateRequestDto;
import com.rnd.inbox.tenant.dto.TenantResponseDto;
import com.rnd.inbox.tenant.dto.TenantStatusUpdateRequestDto;
import com.rnd.inbox.tenant.dto.TenantUpdateRequestDto;
import com.rnd.inbox.tenant.exception.DuplicateSlugException;
import com.rnd.inbox.tenant.exception.TenantNotFoundException;
import com.rnd.inbox.tenant.model.TenantStatus;
import com.rnd.inbox.tenant.service.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.shared.config.AppConfig;
import com.rnd.inbox.shared.config.WebMvcConfig;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.model.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

@WebMvcTest(TenantController.class)
@Import({AppConfig.class, WebMvcConfig.class})
class TenantControllerTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private UserRepository userRepository;

    private UUID tenantId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        actorId = UUID.randomUUID();

        // @RequireRole looks the actor up directly via UserRepository, independently of whatever
        // TenantService is stubbed to do — default to SYSTEM_ADMIN (permitted on every endpoint)
        // so tests unrelated to the coarse role gate aren't incidentally blocked by it.
        stubActorRole(UserRole.SYSTEM_ADMIN);
    }

    private void stubActorRole(UserRole role) {
        User actorEntity = new User();
        actorEntity.setId(actorId);
        actorEntity.setRole(role);
        actorEntity.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actorEntity));
    }

    private TenantResponseDto sampleTenant() {
        return new TenantResponseDto(tenantId, "Acme Inc", "acme", TenantStatus.ACTIVE,
                "Asia/Jerusalem", "he-IL", null, null);
    }

    private TenantCreateRequestDto validCreateRequest() {
        TenantCreateRequestDto request = new TenantCreateRequestDto();
        request.setName("Acme Inc");
        request.setSlug("acme");
        return request;
    }

    private TenantUpdateRequestDto validUpdateRequest() {
        TenantUpdateRequestDto request = new TenantUpdateRequestDto();
        request.setName("Acme Incorporated");
        return request;
    }

    @Test
    void createTenant_withValidRequest_returns201WithLocationHeader() throws Exception {
        when(tenantService.createTenant(any(), eq(actorId))).thenReturn(sampleTenant());

        mockMvc.perform(post("/api/v1/tenants")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/tenants/" + tenantId)))
                .andExpect(jsonPath("$.id").value(tenantId.toString()))
                .andExpect(jsonPath("$.name").value("Acme Inc"))
                .andExpect(jsonPath("$.slug").value("acme"));
    }

    @Test
    void createTenant_withMissingRequiredFields_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/tenants")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TenantCreateRequestDto())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void createTenant_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void createTenant_whenSlugAlreadyExists_returns409() throws Exception {
        when(tenantService.createTenant(any(), eq(actorId))).thenThrow(new DuplicateSlugException("acme"));

        mockMvc.perform(post("/api/v1/tenants")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_SLUG"));
    }

    @Test
    void createTenant_whenActorRoleIsTenantAdmin_returns403WithoutCallingService() throws Exception {
        stubActorRole(UserRole.TENANT_ADMIN);

        mockMvc.perform(post("/api/v1/tenants")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(tenantService, never()).createTenant(any(), any());
    }

    @Test
    void createTenant_whenActorIdUnknownToRepository_returns401() throws Exception {
        when(userRepository.findById(actorId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/tenants")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));

        verify(tenantService, never()).createTenant(any(), any());
    }

    @Test
    void getTenantById_whenFound_returns200() throws Exception {
        when(tenantService.getTenantById(tenantId, actorId)).thenReturn(sampleTenant());

        mockMvc.perform(get("/api/v1/tenants/{id}", tenantId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tenantId.toString()));
    }

    @Test
    void getTenantById_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/{id}", tenantId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void getTenantById_whenNotFound_returns404() throws Exception {
        when(tenantService.getTenantById(tenantId, actorId)).thenThrow(new TenantNotFoundException(tenantId));

        mockMvc.perform(get("/api/v1/tenants/{id}", tenantId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_FOUND"));
    }

    @Test
    void getTenantById_whenActorOutsideScope_returns403() throws Exception {
        when(tenantService.getTenantById(tenantId, actorId))
                .thenThrow(new ForbiddenActionException("Actor cannot act on tenant " + tenantId));

        mockMvc.perform(get("/api/v1/tenants/{id}", tenantId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void listTenants_returns200WithPageResponse() throws Exception {
        PageResponseDto<TenantResponseDto> page = new PageResponseDto<>(List.of(sampleTenant()), 0, 20, 1, 1);
        when(tenantService.listTenants(any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/tenants").header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(tenantId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listTenants_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/tenants"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void listTenants_whenActorRoleIsTenantAdmin_returns403WithoutCallingService() throws Exception {
        stubActorRole(UserRole.TENANT_ADMIN);

        mockMvc.perform(get("/api/v1/tenants").header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(tenantService, never()).listTenants(any());
    }

    @Test
    void updateTenant_withValidRequest_returns200() throws Exception {
        when(tenantService.updateTenant(eq(tenantId), any(), eq(actorId))).thenReturn(sampleTenant());

        mockMvc.perform(patch("/api/v1/tenants/{id}", tenantId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tenantId.toString()));
    }

    @Test
    void updateTenant_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/tenants/{id}", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void updateTenant_whenNotFound_returns404() throws Exception {
        when(tenantService.updateTenant(eq(tenantId), any(), eq(actorId)))
                .thenThrow(new TenantNotFoundException(tenantId));

        mockMvc.perform(patch("/api/v1/tenants/{id}", tenantId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_FOUND"));
    }

    @Test
    void updateTenant_whenActorIsTenantAdminOutsideScope_returns403() throws Exception {
        stubActorRole(UserRole.TENANT_ADMIN);
        when(tenantService.updateTenant(eq(tenantId), any(), eq(actorId)))
                .thenThrow(new ForbiddenActionException("Actor cannot act on tenant " + tenantId));

        mockMvc.perform(patch("/api/v1/tenants/{id}", tenantId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void updateTenant_whenActorRoleIsTeamLead_returns403WithoutCallingService() throws Exception {
        stubActorRole(UserRole.TEAM_LEAD);

        mockMvc.perform(patch("/api/v1/tenants/{id}", tenantId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(tenantService, never()).updateTenant(any(), any(), any());
    }

    @Test
    void updateStatus_withValidStatus_returns200() throws Exception {
        TenantStatusUpdateRequestDto request = new TenantStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");
        when(tenantService.updateStatus(eq(tenantId), any(), eq(actorId))).thenReturn(sampleTenant());

        mockMvc.perform(patch("/api/v1/tenants/{id}/status", tenantId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tenantId.toString()));
    }

    @Test
    void updateStatus_withoutActorHeader_returns401() throws Exception {
        TenantStatusUpdateRequestDto request = new TenantStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");

        mockMvc.perform(patch("/api/v1/tenants/{id}/status", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void updateStatus_whenNotFound_returns404() throws Exception {
        TenantStatusUpdateRequestDto request = new TenantStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");
        when(tenantService.updateStatus(eq(tenantId), any(), eq(actorId)))
                .thenThrow(new TenantNotFoundException(tenantId));

        mockMvc.perform(patch("/api/v1/tenants/{id}/status", tenantId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_FOUND"));
    }

    @Test
    void updateStatus_whenActorRoleIsTenantAdmin_returns403WithoutCallingService() throws Exception {
        stubActorRole(UserRole.TENANT_ADMIN);
        TenantStatusUpdateRequestDto request = new TenantStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");

        mockMvc.perform(patch("/api/v1/tenants/{id}/status", tenantId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(tenantService, never()).updateStatus(any(), any(), any());
    }
}
