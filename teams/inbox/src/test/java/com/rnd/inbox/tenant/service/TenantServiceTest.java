package com.rnd.inbox.tenant.service;
import com.rnd.inbox.tenant.dto.TenantCreateRequestDto;
import com.rnd.inbox.tenant.dto.TenantResponseDto;
import com.rnd.inbox.tenant.dto.TenantStatusUpdateRequestDto;
import com.rnd.inbox.tenant.dto.TenantUpdateRequestDto;
import com.rnd.inbox.tenant.exception.DuplicateSlugException;
import com.rnd.inbox.tenant.exception.TenantNotFoundException;
import com.rnd.inbox.tenant.mapper.TenantMapper;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.model.TenantStatus;
import com.rnd.inbox.tenant.repository.TenantRepository;
import com.rnd.inbox.tenant.security.TenantAuthorization;
import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.model.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.hibernate.exception.ConstraintViolationException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantMapper tenantMapper;

    @Mock
    private AuditService auditService;

    private TenantService tenantService;

    private UUID tenantId;
    private Tenant tenant;
    private UUID actorId;
    private User actor;

    @BeforeEach
    void setUp() {
        tenantService = new TenantService(
                tenantRepository, userRepository, tenantMapper, auditService, new TenantAuthorization());

        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setStatus(TenantStatus.ACTIVE);

        actorId = UUID.randomUUID();
        actor = new User();
        actor.setId(actorId);
        actor.setTenant(tenant);
        actor.setRole(UserRole.SYSTEM_ADMIN);
        actor.setStatus(UserStatus.ACTIVE);
    }

    private TenantCreateRequestDto createRequest(String slug) {
        TenantCreateRequestDto request = new TenantCreateRequestDto();
        request.setName("Acme Inc");
        request.setSlug(slug);
        return request;
    }

    private TenantUpdateRequestDto updateRequest() {
        TenantUpdateRequestDto request = new TenantUpdateRequestDto();
        request.setName("Acme Incorporated");
        request.setTimezone(JsonNullable.of("Asia/Jerusalem"));
        request.setLocaleDefault(JsonNullable.of("he-IL"));
        return request;
    }

    private void stubActorFound() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
    }

    private DataIntegrityViolationException constraintViolation(String constraintName) {
        ConstraintViolationException hibernateException = new ConstraintViolationException(
                "duplicate key value violates unique constraint \"" + constraintName + "\"",
                new SQLException("duplicate key"), constraintName);
        return new DataIntegrityViolationException("could not execute statement", hibernateException);
    }

    @Test
    void createTenant_whenNoDuplicateSlug_savesAndReturnsTenant() {
        stubActorFound();
        TenantCreateRequestDto request = createRequest("acme");
        TenantResponseDto responseDto = new TenantResponseDto(
                tenantId, "Acme Inc", "acme", TenantStatus.ACTIVE, null, null, null, null);

        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.empty());
        when(tenantMapper.toEntity(request)).thenReturn(tenant);
        when(tenantRepository.save(tenant)).thenReturn(tenant);
        when(tenantMapper.toResponseDto(tenant)).thenReturn(responseDto);

        TenantResponseDto result = tenantService.createTenant(request, actorId);

        assertThat(result).isSameAs(responseDto);
        verify(tenantRepository).save(tenant);
        verify(auditService).createAuditLog(eq("TENANT_CREATED"), eq("Tenant"), eq(tenantId), eq(tenant),
                eq(actor), any(), isNull());
    }

    @Test
    void createTenant_whenActorDoesNotExist_throwsUnauthenticatedException() {
        TenantCreateRequestDto request = createRequest("acme");
        when(userRepository.findById(actorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.createTenant(request, actorId))
                .isInstanceOf(UnauthenticatedException.class);

        verify(tenantRepository, never()).save(any());
        verify(auditService, never()).createAuditLog(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createTenant_whenSlugAlreadyExists_throwsDuplicateSlugException() {
        stubActorFound();
        TenantCreateRequestDto request = createRequest("acme");
        Tenant existing = new Tenant();
        existing.setId(UUID.randomUUID());
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> tenantService.createTenant(request, actorId))
                .isInstanceOf(DuplicateSlugException.class);

        verify(tenantRepository, never()).save(any());
    }

    @Test
    void createTenant_whenSaveRacesOnSlug_throwsDuplicateSlugException() {
        stubActorFound();
        TenantCreateRequestDto request = createRequest("acme");
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.empty());
        when(tenantMapper.toEntity(request)).thenReturn(tenant);
        when(tenantRepository.save(tenant)).thenThrow(constraintViolation("uq_tenants_slug"));

        assertThatThrownBy(() -> tenantService.createTenant(request, actorId))
                .isInstanceOf(DuplicateSlugException.class);
    }

    @Test
    void getTenantById_whenActorIsSystemAdmin_returnsAnyTenant() {
        stubActorFound();
        TenantResponseDto responseDto = new TenantResponseDto(
                tenantId, "Acme Inc", "acme", TenantStatus.ACTIVE, null, null, null, null);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantMapper.toResponseDto(tenant)).thenReturn(responseDto);

        TenantResponseDto result = tenantService.getTenantById(tenantId, actorId);

        assertThat(result).isSameAs(responseDto);
    }

    @Test
    void getTenantById_whenActorIsTenantAdminOfSameTenant_returnsTenant() {
        actor.setRole(UserRole.TENANT_ADMIN);
        stubActorFound();
        TenantResponseDto responseDto = new TenantResponseDto(
                tenantId, "Acme Inc", "acme", TenantStatus.ACTIVE, null, null, null, null);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantMapper.toResponseDto(tenant)).thenReturn(responseDto);

        TenantResponseDto result = tenantService.getTenantById(tenantId, actorId);

        assertThat(result).isSameAs(responseDto);
    }

    @Test
    void getTenantById_whenActorIsTenantAdminOfAnotherTenant_throwsForbiddenActionException() {
        actor.setRole(UserRole.TENANT_ADMIN);
        actor.setTenant(new Tenant());
        actor.getTenant().setId(UUID.randomUUID());
        stubActorFound();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertThatThrownBy(() -> tenantService.getTenantById(tenantId, actorId))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void getTenantById_whenActorIsTeamLeadOfSameTenant_returnsTenant() {
        actor.setRole(UserRole.TEAM_LEAD);
        stubActorFound();
        TenantResponseDto responseDto = new TenantResponseDto(
                tenantId, "Acme Inc", "acme", TenantStatus.ACTIVE, null, null, null, null);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantMapper.toResponseDto(tenant)).thenReturn(responseDto);

        TenantResponseDto result = tenantService.getTenantById(tenantId, actorId);

        assertThat(result).isSameAs(responseDto);
    }

    @Test
    void getTenantById_whenActorIsEmployeeOfAnotherTenant_throwsForbiddenActionException() {
        actor.setRole(UserRole.EMPLOYEE);
        actor.setTenant(new Tenant());
        actor.getTenant().setId(UUID.randomUUID());
        stubActorFound();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertThatThrownBy(() -> tenantService.getTenantById(tenantId, actorId))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void getTenantById_whenTenantNotFound_throwsTenantNotFoundException() {
        stubActorFound();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.getTenantById(tenantId, actorId))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void getTenantById_whenActorDoesNotExist_throwsUnauthenticatedException() {
        when(userRepository.findById(actorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.getTenantById(tenantId, actorId))
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void listTenants_returnsAllTenantsMappedToPage() {
        Pageable pageable = PageRequest.of(0, 20);
        TenantResponseDto responseDto = new TenantResponseDto(
                tenantId, "Acme Inc", "acme", TenantStatus.ACTIVE, null, null, null, null);
        Page<Tenant> page = new PageImpl<>(List.of(tenant), pageable, 1);
        when(tenantRepository.findAll(pageable)).thenReturn(page);
        when(tenantMapper.toResponseDto(tenant)).thenReturn(responseDto);

        PageResponseDto<TenantResponseDto> result = tenantService.listTenants(pageable);

        assertThat(result.getContent()).containsExactly(responseDto);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void updateTenant_whenActorIsSystemAdmin_updatesAnyTenant() {
        stubActorFound();
        TenantUpdateRequestDto request = updateRequest();
        TenantResponseDto responseDto = new TenantResponseDto(
                tenantId, "Acme Incorporated", "acme", TenantStatus.ACTIVE, "Asia/Jerusalem", "he-IL", null, null);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(tenant)).thenReturn(tenant);
        when(tenantMapper.toResponseDto(tenant)).thenReturn(responseDto);

        TenantResponseDto result = tenantService.updateTenant(tenantId, request, actorId);

        assertThat(result).isSameAs(responseDto);
        verify(tenantMapper).applyUpdate(tenant, request);
        verify(auditService).createAuditLog(eq("TENANT_UPDATED"), eq("Tenant"), eq(tenantId), eq(tenant),
                eq(actor), any(), isNull());
    }

    @Test
    void updateTenant_whenActorIsTenantAdminOfAnotherTenant_throwsForbiddenActionException() {
        actor.setRole(UserRole.TENANT_ADMIN);
        actor.setTenant(new Tenant());
        actor.getTenant().setId(UUID.randomUUID());
        stubActorFound();
        TenantUpdateRequestDto request = updateRequest();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertThatThrownBy(() -> tenantService.updateTenant(tenantId, request, actorId))
                .isInstanceOf(ForbiddenActionException.class);

        verify(tenantRepository, never()).save(any());
    }

    @Test
    void updateTenant_withOnlyTimezoneProvided_preservesName() {
        stubActorFound();
        TenantUpdateRequestDto request = new TenantUpdateRequestDto();
        request.setTimezone(JsonNullable.of("Asia/Jerusalem"));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(tenant)).thenReturn(tenant);
        when(tenantMapper.toResponseDto(tenant)).thenReturn(
                new TenantResponseDto(tenantId, "Acme Inc", "acme", TenantStatus.ACTIVE,
                        "Asia/Jerusalem", null, null, null));

        tenantService.updateTenant(tenantId, request, actorId);

        verify(tenantMapper).applyUpdate(tenant, request);
    }

    @Test
    void updateTenant_whenTenantNotFound_throwsTenantNotFoundException() {
        stubActorFound();
        TenantUpdateRequestDto request = updateRequest();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.updateTenant(tenantId, request, actorId))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void updateStatus_whenActorIsSystemAdmin_updatesStatus() {
        stubActorFound();
        TenantStatusUpdateRequestDto request = new TenantStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");
        TenantResponseDto responseDto = new TenantResponseDto(
                tenantId, "Acme Inc", "acme", TenantStatus.SUSPENDED, null, null, null, null);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(tenant)).thenReturn(tenant);
        when(tenantMapper.toResponseDto(tenant)).thenReturn(responseDto);

        TenantResponseDto result = tenantService.updateStatus(tenantId, request, actorId);

        assertThat(result).isSameAs(responseDto);
        verify(tenantMapper).applyStatusChange(tenant, TenantStatus.SUSPENDED);
        verify(auditService).createAuditLog(eq("TENANT_STATUS_UPDATED"), eq("Tenant"), eq(tenantId), eq(tenant),
                eq(actor), any(), isNull());
    }

    @Test
    void updateStatus_whenTenantNotFound_throwsTenantNotFoundException() {
        stubActorFound();
        TenantStatusUpdateRequestDto request = new TenantStatusUpdateRequestDto();
        request.setStatus("ARCHIVED");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.updateStatus(tenantId, request, actorId))
                .isInstanceOf(TenantNotFoundException.class);
    }
}
