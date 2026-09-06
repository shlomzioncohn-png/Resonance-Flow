package com.rnd.inbox.service;

import com.rnd.inbox.dto.PageResponseDto;
import com.rnd.inbox.dto.UserCreateRequestDto;
import com.rnd.inbox.dto.UserResponseDto;
import com.rnd.inbox.dto.UserStatusUpdateRequestDto;
import com.rnd.inbox.dto.UserUpdateRequestDto;
import com.rnd.inbox.exception.DuplicateEmailException;
import com.rnd.inbox.exception.DuplicateUsernameException;
import com.rnd.inbox.exception.OrganizationNotFoundException;
import com.rnd.inbox.exception.UnauthenticatedException;
import com.rnd.inbox.exception.UserNotFoundException;
import com.rnd.inbox.mapper.UserMapper;
import com.rnd.inbox.model.entity.Organization;
import com.rnd.inbox.model.entity.User;
import com.rnd.inbox.model.enums.UserStatus;
import com.rnd.inbox.repository.OrganizationRepository;
import com.rnd.inbox.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.openapitools.jackson.nullable.JsonNullable;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private AuditService auditService;

    private UserService userService;

    private UUID organizationId;
    private Organization organization;
    private UUID userId;
    private User user;
    private UUID actorId;
    private User actor;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, organizationRepository, userMapper, auditService);

        organizationId = UUID.randomUUID();
        organization = new Organization();
        organization.setId(organizationId);

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setOrganization(organization);
        user.setStatus(UserStatus.ACTIVE);

        actorId = UUID.randomUUID();
        actor = new User();
        actor.setId(actorId);
    }

    private UserCreateRequestDto createRequest(String email, String username) {
        UserCreateRequestDto request = new UserCreateRequestDto();
        request.setOrganizationId(organizationId.toString());
        request.setDisplayName("Yehudit Cohen");
        request.setEmail(email);
        request.setUsername(username);
        request.setRole("developer");
        return request;
    }

    private UserUpdateRequestDto updateRequest(String email, String username) {
        UserUpdateRequestDto request = new UserUpdateRequestDto();
        request.setDisplayName("Yehudit Cohen");
        request.setEmail(JsonNullable.of(email));
        request.setUsername(JsonNullable.of(username));
        request.setRole("developer");
        return request;
    }

    private void stubActorFound() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
    }

    @Test
    void createUser_whenOrganizationExistsAndNoDuplicates_savesAndReturnsUser() {
        stubActorFound();
        UserCreateRequestDto request = createRequest("yehudit@acme.com", "yehudit");
        UserResponseDto responseDto = new UserResponseDto(userId, "Yehudit Cohen", null, null,
                "yehudit@acme.com", null, "yehudit", "developer", UserStatus.ACTIVE, null, null, null, null);

        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        when(userRepository.findByEmail("yehudit@acme.com")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("yehudit")).thenReturn(Optional.empty());
        when(userMapper.toEntity(request, organization)).thenReturn(user);
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponseDto(user)).thenReturn(responseDto);

        UserResponseDto result = userService.createUser(request, actorId);

        assertThat(result).isSameAs(responseDto);
        verify(userRepository).save(user);
        verify(auditService).createAuditLog(eq("USER_CREATED"), eq("User"), eq(userId), eq(organization),
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
    void createUser_whenOrganizationDoesNotExist_throwsOrganizationNotFoundException() {
        stubActorFound();
        UserCreateRequestDto request = createRequest("yehudit@acme.com", "yehudit");
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.createUser(request, actorId))
                .isInstanceOf(OrganizationNotFoundException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_whenEmailAlreadyExists_throwsDuplicateEmailException() {
        stubActorFound();
        UserCreateRequestDto request = createRequest("yehudit@acme.com", "yehudit");
        User existing = new User();
        existing.setId(UUID.randomUUID());

        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
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

        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        when(userRepository.findByEmail("yehudit@acme.com")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("yehudit")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userService.createUser(request, actorId))
                .isInstanceOf(DuplicateUsernameException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_whenEmailAndUsernameAreNull_skipsUniquenessChecksAndSaves() {
        stubActorFound();
        UserCreateRequestDto request = createRequest(null, null);

        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        when(userMapper.toEntity(request, organization)).thenReturn(user);
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponseDto(user)).thenReturn(new UserResponseDto(
                userId, "Yehudit Cohen", null, null, null, null, null, "developer",
                UserStatus.ACTIVE, null, null, null, null));

        userService.createUser(request, actorId);

        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).findByUsername(any());
        verify(userRepository).save(user);
    }

    @Test
    void getUserById_whenUserExists_returnsUser() {
        UserResponseDto responseDto = new UserResponseDto(userId, "Yehudit Cohen", null, null,
                null, null, null, "developer", UserStatus.ACTIVE, null, null, null, null);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userMapper.toResponseDto(user)).thenReturn(responseDto);

        UserResponseDto result = userService.getUserById(userId);

        assertThat(result).isSameAs(responseDto);
    }

    @Test
    void getUserById_whenUserDoesNotExist_throwsUserNotFoundException() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(userId))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void updateUser_whenUserExists_updatesAndReturnsUser() {
        stubActorFound();
        UserUpdateRequestDto request = updateRequest("new@acme.com", "newname");
        UserResponseDto responseDto = new UserResponseDto(userId, "Yehudit Cohen", null, null,
                "new@acme.com", null, "newname", "developer", UserStatus.ACTIVE, null, null, null, null);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("new@acme.com")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("newname")).thenReturn(Optional.empty());
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponseDto(user)).thenReturn(responseDto);

        UserResponseDto result = userService.updateUser(userId, request, actorId);

        assertThat(result).isSameAs(responseDto);
        verify(userMapper).applyUpdate(user, request);
        verify(userRepository).save(user);
        verify(auditService).createAuditLog(eq("USER_UPDATED"), eq("User"), eq(userId), eq(organization),
                eq(actor), any(), isNull());
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
                userId, "Yehudit Cohen", null, null, "yehudit@acme.com", null, "yehudit", "developer",
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
    void listUsers_mapsEachUserAndPreservesPageMetadata() {
        Pageable pageable = PageRequest.of(0, 2);
        User secondUser = new User();
        secondUser.setId(UUID.randomUUID());
        Page<User> entityPage = new PageImpl<>(List.of(user, secondUser), pageable, 5);

        UserResponseDto firstDto = new UserResponseDto(userId, "Yehudit Cohen", null, null,
                null, null, null, "developer", UserStatus.ACTIVE, null, null, null, null);
        UserResponseDto secondDto = new UserResponseDto(secondUser.getId(), "Someone Else", null, null,
                null, null, null, "developer", UserStatus.ACTIVE, null, null, null, null);

        when(userRepository.findAll(pageable)).thenReturn(entityPage);
        when(userMapper.toResponseDto(user)).thenReturn(firstDto);
        when(userMapper.toResponseDto(secondUser)).thenReturn(secondDto);

        PageResponseDto<UserResponseDto> result = userService.listUsers(pageable);

        assertThat(result.getContent()).containsExactly(firstDto, secondDto);
        assertThat(result.getPage()).isEqualTo(0);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotalElements()).isEqualTo(5);
        assertThat(result.getTotalPages()).isEqualTo(3);
    }

    @Test
    void updateStatus_whenUserExists_updatesStatusAndReturnsUser() {
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");
        UserResponseDto responseDto = new UserResponseDto(userId, "Yehudit Cohen", null, null,
                null, null, null, "developer", UserStatus.SUSPENDED, null, null, null, null);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponseDto(user)).thenReturn(responseDto);

        UserResponseDto result = userService.updateStatus(userId, request);

        assertThat(result).isSameAs(responseDto);
        verify(userMapper).applyStatusChange(user, UserStatus.SUSPENDED);
        verify(userRepository).save(user);
    }

    @Test
    void updateStatus_whenUserDoesNotExist_throwsUserNotFoundException() {
        UserStatusUpdateRequestDto request = new UserStatusUpdateRequestDto();
        request.setStatus("SUSPENDED");
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateStatus(userId, request))
                .isInstanceOf(UserNotFoundException.class);

        verify(userMapper, never()).applyStatusChange(any(), any());
    }
}
