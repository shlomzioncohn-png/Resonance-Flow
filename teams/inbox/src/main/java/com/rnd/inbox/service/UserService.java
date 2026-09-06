package com.rnd.inbox.service;

import com.rnd.inbox.dto.PageResponseDto;
import com.rnd.inbox.dto.UserCreateRequestDto;
import com.rnd.inbox.dto.UserResponseDto;
import com.rnd.inbox.dto.UserStatusUpdateRequestDto;
import com.rnd.inbox.dto.UserUpdateRequestDto;
import com.rnd.inbox.exception.DuplicateEmailException;
import com.rnd.inbox.exception.DuplicateUsernameException;
import com.rnd.inbox.exception.ForbiddenActionException;
import com.rnd.inbox.exception.OrganizationNotFoundException;
import com.rnd.inbox.exception.UnauthenticatedException;
import com.rnd.inbox.exception.UserNotFoundException;
import com.rnd.inbox.mapper.UserMapper;
import com.rnd.inbox.model.entity.Organization;
import com.rnd.inbox.model.entity.User;
import com.rnd.inbox.model.enums.UserStatus;
import com.rnd.inbox.repository.OrganizationRepository;
import com.rnd.inbox.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final UserMapper userMapper;
    private final AuditService auditService;

    public UserService(UserRepository userRepository, OrganizationRepository organizationRepository,
                        UserMapper userMapper, AuditService auditService) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.userMapper = userMapper;
        this.auditService = auditService;
    }

    @Transactional
    public UserResponseDto createUser(UserCreateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Organization organization = findOrganizationOrThrow(UUID.fromString(request.getOrganizationId()));
        assertActorBelongsToOrganization(actor, organization);
        assertEmailAvailable(request.getEmail(), null);
        assertUsernameAvailable(request.getUsername(), null);

        User user = userMapper.toEntity(request, organization);
        User saved = saveUser(user);
        auditService.createAuditLog("USER_CREATED", "User", saved.getId(), organization,
                actor, auditSnapshot(saved), null);
        return userMapper.toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public UserResponseDto getUserById(UUID id, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        User user = findUserOrThrow(id);
        assertActorCanAccessUser(actor, user);
        return userMapper.toResponseDto(user);
    }

    @Transactional
    public UserResponseDto updateUser(UUID id, UserUpdateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        User user = findUserOrThrow(id);
        assertActorCanAccessUser(actor, user);
        assertEmailAvailable(request.getEmail().orElse(null), id);
        assertUsernameAvailable(request.getUsername().orElse(null), id);

        userMapper.applyUpdate(user, request);
        User saved = saveUser(user);
        auditService.createAuditLog("USER_UPDATED", "User", saved.getId(), saved.getOrganization(),
                actor, auditSnapshot(saved), null);
        return userMapper.toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<UserResponseDto> listUsers(Pageable pageable, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Page<UserResponseDto> responsePage = userRepository
                .findAllByOrganizationId(actor.getOrganization().getId(), pageable)
                .map(userMapper::toResponseDto);
        return PageResponseDto.from(responsePage);
    }

    @Transactional
    public UserResponseDto updateStatus(UUID id, UserStatusUpdateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        User user = findUserOrThrow(id);
        assertActorCanAccessUser(actor, user);

        userMapper.applyStatusChange(user, UserStatus.valueOf(request.getStatus()));
        User saved = userRepository.save(user);
        auditService.createAuditLog("USER_STATUS_UPDATED", "User", saved.getId(), saved.getOrganization(),
                actor, auditSnapshot(saved), null);
        return userMapper.toResponseDto(saved);
    }

    private Organization findOrganizationOrThrow(UUID id) {
        return organizationRepository.findById(id)
                .orElseThrow(() -> new OrganizationNotFoundException(id));
    }

    private User findUserOrThrow(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    private User findActorOrThrow(UUID actorId) {
        return userRepository.findById(actorId)
                .orElseThrow(() -> new UnauthenticatedException("Actor " + actorId + " does not exist"));
    }

    private Map<String, Object> auditSnapshot(User user) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("displayName", user.getDisplayName());
        snapshot.put("firstName", user.getFirstName());
        snapshot.put("lastName", user.getLastName());
        snapshot.put("email", user.getEmail());
        snapshot.put("phone", user.getPhone());
        snapshot.put("username", user.getUsername());
        snapshot.put("role", user.getRole());
        snapshot.put("status", user.getStatus().name());
        return snapshot;
    }

    private void assertActorCanAccessUser(User actor, User targetUser) {
        assertActorBelongsToOrganization(actor, targetUser.getOrganization());
    }

    private void assertActorBelongsToOrganization(User actor, Organization organization) {
        if (!actor.getOrganization().getId().equals(organization.getId())) {
            throw new ForbiddenActionException(
                    "Actor " + actor.getId() + " cannot act on organization " + organization.getId());
        }
    }

    /**
     * Saves the user and translates a concurrent unique-constraint violation into the same
     * DuplicateEmailException/DuplicateUsernameException the pre-check throws. The pre-checks in
     * assertEmailAvailable/assertUsernameAvailable read-then-decide, so two requests racing on the
     * same email/username can both pass them before either has saved; the database's unique index
     * (uq_users_email / uq_users_username) is what actually stops the second write, and this is
     * what turns that low-level rejection into the normal 409 response instead of a raw 500.
     */
    private User saveUser(User user) {
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            throw translateUniqueConstraintViolation(user, e);
        }
    }

    private RuntimeException translateUniqueConstraintViolation(User user, DataIntegrityViolationException e) {
        String constraintName = extractConstraintName(e);
        if ("uq_users_email".equals(constraintName)) {
            return new DuplicateEmailException(user.getEmail());
        }
        if ("uq_users_username".equals(constraintName)) {
            return new DuplicateUsernameException(user.getUsername());
        }
        // Not a violation we know how to translate (e.g. a future constraint) - don't guess, surface it as-is.
        throw e;
    }

    private String extractConstraintName(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                return constraintViolation.getConstraintName();
            }
        }
        return null;
    }

    private void assertEmailAvailable(String email, UUID excludingUserId) {
        if (email == null) {
            return;
        }
        userRepository.findByEmail(email).ifPresent(existing -> {
            if (!existing.getId().equals(excludingUserId)) {
                throw new DuplicateEmailException(email);
            }
        });
    }

    private void assertUsernameAvailable(String username, UUID excludingUserId) {
        if (username == null) {
            return;
        }
        userRepository.findByUsername(username).ifPresent(existing -> {
            if (!existing.getId().equals(excludingUserId)) {
                throw new DuplicateUsernameException(username);
            }
        });
    }
}
