package com.rnd.inbox.user.service;
import com.rnd.inbox.user.dto.UserCreateRequestDto;
import com.rnd.inbox.user.dto.UserResponseDto;
import com.rnd.inbox.user.dto.UserStatusUpdateRequestDto;
import com.rnd.inbox.user.dto.UserUpdateRequestDto;
import com.rnd.inbox.user.exception.DuplicateEmailException;
import com.rnd.inbox.user.exception.DuplicateUsernameException;
import com.rnd.inbox.user.exception.UserNotFoundException;
import com.rnd.inbox.user.mapper.UserMapper;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.model.UserStatus;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.user.security.UserAuthorization;
import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.exception.TenantNotFoundException;
import com.rnd.inbox.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.openapitools.jackson.nullable.JsonNullable;
import org.hibernate.exception.ConstraintViolationException;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
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
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private AuditService auditService;

    private UserService userService;

    private UUID tenantId;
    private Tenant tenant;
    private UUID userId;
    private User user;
    private UUID actorId;
    private User actor;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository, tenantRepository, userMapper, auditService, new UserAuthorization(userRepository));

        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setTenant(tenant);
        user.setStatus(UserStatus.ACTIVE);

        actorId = UUID.randomUUID();
        actor = new User();
        actor.setId(actorId);
        actor.setTenant(tenant);
        actor.setRole(UserRole.TENANT_ADMIN);
        actor.setStatus(UserStatus.ACTIVE);
    }

    private UserCreateRequestDto createRequest(String email, String username) {
        UserCreateRequestDto request = new UserCreateRequestDto();
        request.setTenantId(tenantId.toString());
        request.setDisplayName("Yehudit Cohen");
        request.setEmail(email);
        request.setUsername(username);
        request.setRole("EMPLOYEE");
        return request;
    }

    private UserUpdateRequestDto updateRequest(String email, String username) {
        UserUpdateRequestDto request = new UserUpdateRequestDto();
        request.setDisplayName("Yehudit Cohen");
        request.setEmail(JsonNullable.of(email));
        request.setUsername(JsonNullable.of(username));
        request.setRole("EMPLOYEE");
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
    void createUser_whenTenantExistsAndNoDuplicates_savesAndReturnsUser() {
        stubActorFound();
        UserCreateRequestDto request = createRequest("yehudit@acme.com", "yehudit");
        UserResponseDto responseDto = new UserResponseDto(userId, "Yehudit Cohen", null, null,
                "yehudit@acme.com", null, "yehudit", UserRole.EMPLOYEE, UserStatus.ACTIVE, null, null, null, null);

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(userRepository.findByEmail("yehudit@acme.com")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("yehudit")).thenReturn(Optional.empty());
        when(userMapper.toEntity(request, tenant)).thenReturn(user);
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponseDto(user)).thenReturn(responseDto);

        UserResponseDto result = userService.createUser(request, actorId);

        assertThat(result).isSameAs(responseDto);
        verify(userRepository).save(user);
        verify(auditService).createAuditLog(eq("USER_CREATED"), eq("User"), eq(userId), eq(tenant),
                eq(actor), any(), isNull());
    }

    @Test
    void createUser_whenActorDoesNotExist_throwsUnauthenticatedException() {
        UserCreateRequestDto request = createRequest("yehudit@acme.com", "yehudit");
        when(userRepository.findById(actorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.createUser(request, actorId))
                .isInstanceOf(UnauthenticatedException.class);

        verify(userRepository, never()).save(any());
        verify(auditService, never()).createAuditLog(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createUser_whenTenantDoesNotExist_throwsTenantNotFoundException() {
        stubActorFound();
        UserCreateRequestDto request = createRequest("yehudit@acme.com", "yehudit");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.createUser(request, actorId))
                .isInstanceOf(TenantNotFoundException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_whenActorBelongsToAnotherTenant_throwsForbiddenActionException() {
        stubActorFound();
        actor.setTenant(new Tenant());
        actor.getTenant().setId(UUID.randomUUID());
        UserCreateRequestDto request = createRequest("yehudit@acme.com", "yehudit");

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertThatThrownBy(() -> userService.createUser(request, actorId))
                .isInstanceOf(ForbiddenActionException.class);

        verify(userRepository, never()).save(any());
        verify(auditService, never()).createAuditLog(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createUser_whenActorIsSystemAdmin_ignoresTenant() {
        actor.setRole(UserRole.SYSTEM_ADMIN);
        actor.setTenant(new Tenant());
        actor.getTenant().setId(UUID.randomUUID());
        stubActorFound();
        UserCreateRequestDto request = createRequest("yehudit@acme.com", "yehudit");

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(userMapper.toEntity(request, tenant)).thenReturn(user);
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponseDto(user)).thenReturn(new UserResponseDto(
                userId, "Yehudit Cohen", null, null, "yehudit@acme.com", null, "yehudit",
                UserRole.EMPLOYEE, UserStatus.ACTIVE, null, null, null, null));

        userService.createUser(request, actorId);

        verify(userRepository).save(user);
    }

    @Test
    void createUser_whenActorIsTeamLead_throwsForbiddenActionExceptionEvenInOwnTenant() {
        actor.setRole(UserRole.TEAM_LEAD);
        stubActorFound();
        UserCreateRequestDto request = createRequest("yehudit@acme.com", "yehudit");

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertThatThrownBy(() -> userService.createUser(request, actorId))
                .isInstanceOf(ForbiddenActionException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_whenActorIsEmployee_throwsForbiddenActionExceptionEvenInOwnTenant() {
        actor.setRole(UserRole.EMPLOYEE);
        stubActorFound();
        UserCreateRequestDto request = createRequest("yehudit@acme.com", "yehudit");

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertThatThrownBy(() -> userService.createUser(request, actorId))
                .isInstanceOf(ForbiddenActionException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_whenEmailAlreadyExists_throwsDuplicateEmailException() {
        stubActorFound();
        UserCreateRequestDto request = createRequest("yehudit@acme.com", "yehudit");
        User existing = new User();
        existing.setId(UUID.randomUUID());

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(userRepository.findByEmail("yehudit@acme.com")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userService.createUser(request, actorId))
                .isInstanceOf(DuplicateEmailException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_whenUsernameAlreadyExists_throwsDuplicateUsernameException() {
        stubActorFound();
        UserCreateRequestDto request = createRequest("yehudit@acme.com", "yehudit");
        User existing = new User();
        existing.setId(UUID.randomUUID());

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(userRepository.findByEmail("yehudit@acme.com")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("yehudit")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userService.createUser(request, actorId))
                .isInstanceOf(DuplicateUsernameException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_whenEmailUniqueConstraintViolatedConcurrently_throwsDuplicateEmailException() {
        stubActorFound();
        UserCreateRequestDto request = createRequest("race@acme.com", "racer");
        user.setEmail("race@acme.com");

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(userRepository.findByEmail("race@acme.com")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("racer")).thenReturn(Optional.empty());
        when(userMapper.toEntity(request, tenant)).thenReturn(user);
        when(userRepository.save(user)).thenThrow(constraintViolation("uq_users_email"));

        assertThatThrownBy(() -> userService.createUser(request, actorId))
                .isInstanceOf(DuplicateEmailException.class);

        verify(auditService, never()).createAuditLog(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createUser_whenUnrecognizedConstraintViolated_rethrowsOriginalException() {
        stubActorFound();
        UserCreateRequestDto request = createRequest("yehudit@acme.com", "yehudit");
        DataIntegrityViolationException original = constraintViolation("uq_users_phone");

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(userRepository.findByEmail("yehudit@acme.com")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("yehudit")).thenReturn(Optional.empty());
        when(userMapper.toEntity(request, tenant)).thenReturn(user);
        when(userRepository.save(user)).thenThrow(original);

        assertThatThrownBy(() -> userService.createUser(request, actorId))
                .isSameAs(original);
    }

    @Test
    void createUser_whenEmailAndUsernameAreNull_skipsUniquenessChecksAndSaves() {
        stubActorFound();
        UserCreateRequestDto request = createRequest(null, null);

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(userMapper.toEntity(request, tenant)).thenReturn(user);
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponseDto(user)).thenReturn(new UserResponseDto(
                userId, "Yehudit Cohen", null, null, null, null, null, UserRole.EMPLOYEE,
                UserStatus.ACTIVE, null, null, null, null));

        userService.createUser(request, actorId);

        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).findByUsername(any());
        verify(userRepository).save(user);
    }

    @Test
    void getUserById_whenUserExists_returnsUser() {
        stubActorFound();
        UserResponseDto responseDto = new UserResponseDto(userId, "Yehudit Cohen", null, null,
                null, null, null, UserRole.EMPLOYEE, UserStatus.ACTIVE, null, null, null, null);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userMapper.toResponseDto(user)).thenReturn(responseDto);

        UserResponseDto result = userService.getUserById(userId, actorId);

        assertThat(result).isSameAs(responseDto);
    }

    @Test
    void getUserById_whenActorDoesNotExist_throwsUnauthenticatedException() {
        when(userRepository.findById(actorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(userId, actorId))
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void getUserById_whenUserDoesNotExist_throwsUserNotFoundException() {
        stubActorFound();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(userId, actorId))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void getUserById_whenActorBelongsToAnotherTenant_throwsForbiddenActionException() {
        stubActorFound();
        actor.setTenant(new Tenant());
        actor.getTenant().setId(UUID.randomUUID());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.getUserById(userId, actorId))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void getUserById_whenActorIsSystemAdmin_ignoresTenant() {
        actor.setRole(UserRole.SYSTEM_ADMIN);
        actor.setTenant(new Tenant());
        actor.getTenant().setId(UUID.randomUUID());
        stubActorFound();
        UserResponseDto responseDto = new UserResponseDto(userId, "Yehudit Cohen", null, null,
                null, null, null, UserRole.EMPLOYEE, UserStatus.ACTIVE, null, null, null, null);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userMapper.toResponseDto(user)).thenReturn(responseDto);

        UserResponseDto result = userService.getUserById(userId, actorId);

        assertThat(result).isSameAs(responseDto);
    }

    @Test
    void getUserById_whenActorIsTeamLeadAndTargetIsOnTheirTeam_returnsUser() {
        actor.setRole(UserRole.TEAM_LEAD);
        stubActorFound();
        UserResponseDto responseDto = new UserResponseDto(userId, "Yehudit Cohen", null, null,
                null, null, null, UserRole.EMPLOYEE, UserStatus.ACTIVE, null, null, null, null);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsInTeamLedBy(userId, actorId)).thenReturn(true);
        when(userMapper.toResponseDto(user)).thenReturn(responseDto);

        UserResponseDto result = userService.getUserById(userId, actorId);

        assertThat(result).isSameAs(responseDto);
    }

    @Test
    void getUserById_whenActorIsTeamLeadAndTargetIsNotOnTheirTeam_throwsForbiddenActionException() {
        actor.setRole(UserRole.TEAM_LEAD);
        stubActorFound();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsInTeamLedBy(userId, actorId)).thenReturn(false);

        assertThatThrownBy(() -> userService.getUserById(userId, actorId))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void getUserById_whenActorIsEmployeeAndTargetIsThemselves_returnsUser() {
        actor.setRole(UserRole.EMPLOYEE);
        stubActorFound();
        UserResponseDto responseDto = new UserResponseDto(actorId, "Yehudit Cohen", null, null,
                null, null, null, UserRole.EMPLOYEE, UserStatus.ACTIVE, null, null, null, null);

        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(userMapper.toResponseDto(actor)).thenReturn(responseDto);

        UserResponseDto result = userService.getUserById(actorId, actorId);

        assertThat(result).isSameAs(responseDto);
    }

    @Test
    void getUserById_whenActorIsEmployeeAndTargetIsSomeoneElse_throwsForbiddenActionException() {
        actor.setRole(UserRole.EMPLOYEE);
        stubActorFound();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.getUserById(userId, actorId))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void updateUser_whenUserExists_updatesAndReturnsUser() {
        stubActorFound();
        UserUpdateRequestDto request = updateRequest("new@acme.com", "newname");
        UserResponseDto responseDto = new UserResponseDto(userId, "Yehudit Cohen", null, null,
                "new@acme.com", null, "newname", UserRole.EMPLOYEE, UserStatus.ACTIVE, null, null, null, null);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("new@acme.com")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("newname")).thenReturn(Optional.empty());
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponseDto(user)).thenReturn(responseDto);

        UserResponseDto result = userService.updateUser(userId, request, actorId);

        assertThat(result).isSameAs(responseDto);
        verify(userMapper).applyUpdate(user, request);
        verify(userRepository).save(user);
        verify(auditService).createAuditLog(eq("USER_UPDATED"), eq("User"), eq(userId), eq(tenant),
                eq(actor), any(), isNull());
    }

    @Test
    void updateUser_writesAuditSnapshotWithAllUserFields() {
        stubActorFound();
        user.setDisplayName("Yehudit Cohen");
        user.setFirstName("Yehudit");
        user.setLastName("Cohen");
        user.setEmail("yehudit@acme.com");
        user.setPhone("0501234567");
        user.setUsername("yehudit");
        user.setRole(UserRole.EMPLOYEE);
        UserUpdateRequestDto request = updateRequest("yehudit@acme.com", "yehudit");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("yehudit@acme.com")).thenReturn(Optional.of(user));
        when(userRepository.findByUsername("yehudit")).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        userService.updateUser(userId, request, actorId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> snapshotCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).createAuditLog(eq("USER_UPDATED"), eq("User"), eq(userId), eq(tenant),
                eq(actor), snapshotCaptor.capture(), isNull());
        assertThat(snapshotCaptor.getValue())
                .containsEntry("displayName", "Yehudit Cohen")
                .containsEntry("firstName", "Yehudit")
                .containsEntry("lastName", "Cohen")
                .containsEntry("email", "yehudit@acme.com")
                .containsEntry("phone", "0501234567")
                .containsEntry("username", "yehudit")
                .containsEntry("role", UserRole.EMPLOYEE)
                .containsEntry("status", "ACTIVE");
    }

    @Test
    void updateUser_whenActorDoesNotExist_throwsUnauthenticatedException() {
        UserUpdateRequestDto request = updateRequest("new@acme.com", "newname");
        when(userRepository.findById(actorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUser(userId, request, actorId))
                .isInstanceOf(UnauthenticatedException.class);

        verify(userMapper, never()).applyUpdate(any(), any());
        verify(auditService, never()).createAuditLog(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateUser_whenUserDoesNotExist_throwsUserNotFoundException() {
        stubActorFound();
        UserUpdateRequestDto request = updateRequest("new@acme.com", "newname");
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUser(userId, request, actorId))
                .isInstanceOf(UserNotFoundException.class);

        verify(userMapper, never()).applyUpdate(any(), any());
    }

    @Test
    void updateUser_whenActorBelongsToAnotherTenant_throwsForbiddenActionException() {
        stubActorFound();
        actor.setTenant(new Tenant());
        actor.getTenant().setId(UUID.randomUUID());
        UserUpdateRequestDto request = updateRequest("new@acme.com", "newname");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.updateUser(userId, request, actorId))
                .isInstanceOf(ForbiddenActionException.class);

        verify(userMapper, never()).applyUpdate(any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUser_whenActorIsSystemAdmin_ignoresTenant() {
        actor.setRole(UserRole.SYSTEM_ADMIN);
        actor.setTenant(new Tenant());
        actor.getTenant().setId(UUID.randomUUID());
        stubActorFound();
        UserUpdateRequestDto request = updateRequest("new@acme.com", "newname");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("new@acme.com")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("newname")).thenReturn(Optional.empty());
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponseDto(user)).thenReturn(new UserResponseDto(
                userId, "Yehudit Cohen", null, null, "new@acme.com", null, "newname",
                UserRole.EMPLOYEE, UserStatus.ACTIVE, null, null, null, null));

        userService.updateUser(userId, request, actorId);

        verify(userMapper).applyUpdate(user, request);
    }

    @Test
    void updateUser_whenActorIsTeamLeadAndTargetIsOnTheirTeam_updatesAndReturnsUser() {
        actor.setRole(UserRole.TEAM_LEAD);
        stubActorFound();
        UserUpdateRequestDto request = updateRequest("new@acme.com", "newname");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsInTeamLedBy(userId, actorId)).thenReturn(true);
        when(userRepository.findByEmail("new@acme.com")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("newname")).thenReturn(Optional.empty());
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponseDto(user)).thenReturn(new UserResponseDto(
                userId, "Yehudit Cohen", null, null, "new@acme.com", null, "newname",
                UserRole.EMPLOYEE, UserStatus.ACTIVE, null, null, null, null));

        userService.updateUser(userId, request, actorId);

        verify(userMapper).applyUpdate(user, request);
    }

    @Test
    void updateUser_whenActorIsTeamLeadAndTargetIsNotOnTheirTeam_throwsForbiddenActionException() {
        actor.setRole(UserRole.TEAM_LEAD);
        stubActorFound();
        UserUpdateRequestDto request = updateRequest("new@acme.com", "newname");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsInTeamLedBy(userId, actorId)).thenReturn(false);

        assertThatThrownBy(() -> userService.updateUser(userId, request, actorId))
                .isInstanceOf(ForbiddenActionException.class);

        verify(userMapper, never()).applyUpdate(any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUser_whenActorIsEmployeeAndTargetIsThemselves_updatesAndReturnsUser() {
        actor.setRole(UserRole.EMPLOYEE);
        stubActorFound();
        UserUpdateRequestDto request = updateRequest("new@acme.com", "newname");

        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(userRepository.findByEmail("new@acme.com")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("newname")).thenReturn(Optional.empty());
        when(userRepository.save(actor)).thenReturn(actor);
        when(userMapper.toResponseDto(actor)).thenReturn(new UserResponseDto(
                actorId, "Yehudit Cohen", null, null, "new@acme.com", null, "newname",
                UserRole.EMPLOYEE, UserStatus.ACTIVE, null, null, null, null));

        userService.updateUser(actorId, request, actorId);

        verify(userMapper).applyUpdate(actor, request);
    }

    @Test
    void updateUser_whenActorIsEmployeeAndTargetIsSomeoneElse_throwsForbiddenActionException() {
        actor.setRole(UserRole.EMPLOYEE);
        stubActorFound();
        UserUpdateRequestDto request = updateRequest("new@acme.com", "newname");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.updateUser(userId, request, actorId))
                .isInstanceOf(ForbiddenActionException.class);

        verify(userMapper, never()).applyUpdate(any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUser_whenEmailBelongsToAnotherUser_throwsDuplicateEmailException() {
        stubActorFound();
        UserUpdateRequestDto request = updateRequest("taken@acme.com", "newname");
        User anotherUser = new User();
        anotherUser.setId(UUID.randomUUID());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("taken@acme.com")).thenReturn(Optional.of(anotherUser));

        assertThatThrownBy(() -> userService.updateUser(userId, request, actorId))
                .isInstanceOf(DuplicateEmailException.class);

        verify(userMapper, never()).applyUpdate(any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUser_whenEmailBelongsToTheSameUser_doesNotThrowAndSaves() {
        stubActorFound();
        UserUpdateRequestDto request = updateRequest("yehudit@acme.com", "yehudit");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("yehudit@acme.com")).thenReturn(Optional.of(user));
        when(userRepository.findByUsername("yehudit")).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponseDto(user)).thenReturn(new UserResponseDto(
                userId, "Yehudit Cohen", null, null, "yehudit@acme.com", null, "yehudit", UserRole.EMPLOYEE,
                UserStatus.ACTIVE, null, null, null, null));

        userService.updateUser(userId, request, actorId);

        verify(userMapper).applyUpdate(user, request);
        verify(userRepository).save(user);
    }

    @Test
    void updateUser_whenUsernameBelongsToAnotherUser_throwsDuplicateUsernameException() {
        stubActorFound();
        UserUpdateRequestDto request = updateRequest("yehudit@acme.com", "taken");
        User anotherUser = new User();
        anotherUser.setId(UUID.randomUUID());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("yehudit@acme.com")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("taken")).thenReturn(Optional.of(anotherUser));

        assertThatThrownBy(() -> userService.updateUser(userId, request, actorId))
                .isInstanceOf(DuplicateUsernameException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUser_whenUsernameUniqueConstraintViolatedConcurrently_throwsDuplicateUsernameException() {
        stubActorFound();
        user.setUsername("racer");
        UserUpdateRequestDto request = updateRequest("yehudit@acme.com", "racer");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("yehudit@acme.com")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("racer")).thenReturn(Optional.empty());
        when(userRepository.save(user)).thenThrow(constraintViolation("uq_users_username"));

        assertThatThrownBy(() -> userService.updateUser(userId, request, actorId))
                .isInstanceOf(DuplicateUsernameException.class);

        verify(auditService, never()).createAuditLog(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void listUsers_whenActorIsTenantAdmin_mapsEachUserAndPreservesPageMetadata() {
        actor.setRole(UserRole.TENANT_ADMIN);
        stubActorFound();
        Pageable pageable = PageRequest.of(0, 2);
        User secondUser = new User();
        secondUser.setId(UUID.randomUUID());
        secondUser.setTenant(tenant);
        Page<User> entityPage = new PageImpl<>(List.of(user, secondUser), pageable, 5);

        UserResponseDto firstDto = new UserResponseDto(userId, "Yehudit Cohen", null, null,
                null, null, null, UserRole.EMPLOYEE, UserStatus.ACTIVE, null, null, null, null);
        UserResponseDto secondDto = new UserResponseDto(secondUser.getId(), "Someone Else", null, null,
                null, null, null, UserRole.EMPLOYEE, UserStatus.ACTIVE, null, null, null, null);

        when(userRepository.findAllByTenantId(tenantId, pageable)).thenReturn(entityPage);
        when(userMapper.toResponseDto(user)).thenReturn(firstDto);
        when(userMapper.toResponseDto(secondUser)).thenReturn(secondDto);

        PageResponseDto<UserResponseDto> result = userService.listUsers(pageable, actorId);

        assertThat(result.getContent()).containsExactly(firstDto, secondDto);
        assertThat(result.getPage()).isEqualTo(0);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotalElements()).isEqualTo(5);
        assertThat(result.getTotalPages()).isEqualTo(3);
    }

    @Test
    void listUsers_whenActorIsSystemAdmin_queriesAcrossAllTenants() {
        actor.setRole(UserRole.SYSTEM_ADMIN);
        stubActorFound();
        Pageable pageable = PageRequest.of(0, 2);
        Page<User> entityPage = new PageImpl<>(List.of(user), pageable, 1);
        UserResponseDto dto = new UserResponseDto(userId, "Yehudit Cohen", null, null,
                null, null, null, UserRole.EMPLOYEE, UserStatus.ACTIVE, null, null, null, null);

        when(userRepository.findAll(pageable)).thenReturn(entityPage);
        when(userMapper.toResponseDto(user)).thenReturn(dto);

        PageResponseDto<UserResponseDto> result = userService.listUsers(pageable, actorId);

        assertThat(result.getContent()).containsExactly(dto);
        verify(userRepository, never()).findAllByTenantId(any(), any());
    }

    @Test
    void listUsers_whenActorIsTeamLead_queriesByTeamLeadId() {
        actor.setRole(UserRole.TEAM_LEAD);
        stubActorFound();
        Pageable pageable = PageRequest.of(0, 2);
        Page<User> entityPage = new PageImpl<>(List.of(user), pageable, 1);
        UserResponseDto dto = new UserResponseDto(userId, "Yehudit Cohen", null, null,
                null, null, null, UserRole.EMPLOYEE, UserStatus.ACTIVE, null, null, null, null);

        when(userRepository.findAllByTeamLeadId(actorId, pageable)).thenReturn(entityPage);
        when(userMapper.toResponseDto(user)).thenReturn(dto);

        PageResponseDto<UserResponseDto> result = userService.listUsers(pageable, actorId);

        assertThat(result.getContent()).containsExactly(dto);
        verify(userRepository, never()).findAllByTenantId(any(), any());
    }

    @Test
    void listUsers_whenActorIsEmployee_returnsOnlyThemselvesWithoutAQuery() {
        actor.setRole(UserRole.EMPLOYEE);
        stubActorFound();
        UserResponseDto dto = new UserResponseDto(actorId, "Yehudit Cohen", null, null,
                null, null, null, UserRole.EMPLOYEE, UserStatus.ACTIVE, null, null, null, null);
        when(userMapper.toResponseDto(actor)).thenReturn(dto);

        PageResponseDto<UserResponseDto> result = userService.listUsers(PageRequest.of(0, 20), actorId);

        assertThat(result.getContent()).containsExactly(dto);
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(userRepository, never()).findAllByTenantId(any(), any());
        verify(userRepository, never()).findAllByTeamLeadId(any(), any());
        verify(userRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void listUsers_whenActorIsEmployee_secondPageIsEmpty() {
        actor.setRole(UserRole.EMPLOYEE);
        stubActorFound();

        PageResponseDto<UserResponseDto> result = userService.listUsers(PageRequest.of(1, 20), actorId);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void listUsers_whenActorDoesNotExist_throwsUnauthenticatedException() {
        when(userRepository.findById(actorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.listUsers(PageRequest.of(0, 2), actorId))
                .isInstanceOf(UnauthenticatedException.class);

        verify(userRepository, never()).findAllByTenantId(any(), any());
    }

    @Test
    void updateStatus_whenUserExists_updatesStatusAndReturnsUser() {
        stubActorFound();
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");
        UserResponseDto responseDto = new UserResponseDto(userId, "Yehudit Cohen", null, null,
                null, null, null, UserRole.EMPLOYEE, UserStatus.SUSPENDED, null, null, null, null);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponseDto(user)).thenReturn(responseDto);

        UserResponseDto result = userService.updateStatus(userId, request, actorId);

        assertThat(result).isSameAs(responseDto);
        verify(userMapper).applyStatusChange(user, UserStatus.SUSPENDED);
        verify(userRepository).save(user);
        verify(auditService).createAuditLog(eq("USER_STATUS_UPDATED"), eq("User"), eq(userId), eq(tenant),
                eq(actor), any(), isNull());
    }

    @Test
    void updateStatus_whenActorDoesNotExist_throwsUnauthenticatedException() {
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");
        when(userRepository.findById(actorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateStatus(userId, request, actorId))
                .isInstanceOf(UnauthenticatedException.class);

        verify(userMapper, never()).applyStatusChange(any(), any());
        verify(auditService, never()).createAuditLog(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateStatus_whenUserDoesNotExist_throwsUserNotFoundException() {
        stubActorFound();
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateStatus(userId, request, actorId))
                .isInstanceOf(UserNotFoundException.class);

        verify(userMapper, never()).applyStatusChange(any(), any());
    }

    @Test
    void updateStatus_whenActorBelongsToAnotherTenant_throwsForbiddenActionException() {
        stubActorFound();
        actor.setTenant(new Tenant());
        actor.getTenant().setId(UUID.randomUUID());
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.updateStatus(userId, request, actorId))
                .isInstanceOf(ForbiddenActionException.class);

        verify(userMapper, never()).applyStatusChange(any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateStatus_whenActorIsSystemAdmin_ignoresTenant() {
        actor.setRole(UserRole.SYSTEM_ADMIN);
        actor.setTenant(new Tenant());
        actor.getTenant().setId(UUID.randomUUID());
        stubActorFound();
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponseDto(user)).thenReturn(new UserResponseDto(
                userId, "Yehudit Cohen", null, null, null, null, null,
                UserRole.EMPLOYEE, UserStatus.SUSPENDED, null, null, null, null));

        userService.updateStatus(userId, request, actorId);

        verify(userMapper).applyStatusChange(user, UserStatus.SUSPENDED);
    }

    @Test
    void updateStatus_whenActorIsTeamLeadAndTargetIsOnTheirTeam_updatesStatus() {
        actor.setRole(UserRole.TEAM_LEAD);
        stubActorFound();
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsInTeamLedBy(userId, actorId)).thenReturn(true);
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponseDto(user)).thenReturn(new UserResponseDto(
                userId, "Yehudit Cohen", null, null, null, null, null,
                UserRole.EMPLOYEE, UserStatus.SUSPENDED, null, null, null, null));

        userService.updateStatus(userId, request, actorId);

        verify(userMapper).applyStatusChange(user, UserStatus.SUSPENDED);
    }

    @Test
    void updateStatus_whenActorIsTeamLeadAndTargetIsNotOnTheirTeam_throwsForbiddenActionException() {
        actor.setRole(UserRole.TEAM_LEAD);
        stubActorFound();
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsInTeamLedBy(userId, actorId)).thenReturn(false);

        assertThatThrownBy(() -> userService.updateStatus(userId, request, actorId))
                .isInstanceOf(ForbiddenActionException.class);

        verify(userMapper, never()).applyStatusChange(any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateStatus_whenActorIsEmployeeAndTargetIsThemselves_updatesStatus() {
        actor.setRole(UserRole.EMPLOYEE);
        stubActorFound();
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");

        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(userRepository.save(actor)).thenReturn(actor);
        when(userMapper.toResponseDto(actor)).thenReturn(new UserResponseDto(
                actorId, "Yehudit Cohen", null, null, null, null, null,
                UserRole.EMPLOYEE, UserStatus.SUSPENDED, null, null, null, null));

        userService.updateStatus(actorId, request, actorId);

        verify(userMapper).applyStatusChange(actor, UserStatus.SUSPENDED);
    }

    @Test
    void updateStatus_whenActorIsEmployeeAndTargetIsSomeoneElse_throwsForbiddenActionException() {
        actor.setRole(UserRole.EMPLOYEE);
        stubActorFound();
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.updateStatus(userId, request, actorId))
                .isInstanceOf(ForbiddenActionException.class);

        verify(userMapper, never()).applyStatusChange(any(), any());
        verify(userRepository, never()).save(any());
    }
}
