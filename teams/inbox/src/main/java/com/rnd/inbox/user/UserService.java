package com.rnd.inbox.user;

import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.tenant.TenantNotFoundException;
import com.rnd.inbox.tenant.TenantRepository;
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
    private final TenantRepository tenantRepository;
    private final UserMapper userMapper;
    private final AuditService auditService;

    public UserService(UserRepository userRepository, TenantRepository tenantRepository,
                        UserMapper userMapper, AuditService auditService) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.userMapper = userMapper;
        this.auditService = auditService;
    }

    @Transactional
    public UserResponseDto createUser(UserCreateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Tenant tenant = findTenantOrThrow(UUID.fromString(request.getTenantId()));
        assertActorBelongsToTenant(actor, tenant);
        assertEmailAvailable(request.getEmail(), null);
        assertUsernameAvailable(request.getUsername(), null);

        User user = userMapper.toEntity(request, tenant);
        User saved = saveUser(user);
        auditService.createAuditLog("USER_CREATED", "User", saved.getId(), tenant,
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
        auditService.createAuditLog("USER_UPDATED", "User", saved.getId(), saved.getTenant(),
                actor, auditSnapshot(saved), null);
        return userMapper.toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<UserResponseDto> listUsers(Pageable pageable, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Page<UserResponseDto> responsePage = userRepository
                .findAllByTenantId(actor.getTenant().getId(), pageable)
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
        auditService.createAuditLog("USER_STATUS_UPDATED", "User", saved.getId(), saved.getTenant(),
                actor, auditSnapshot(saved), null);
        return userMapper.toResponseDto(saved);
    }

    private Tenant findTenantOrThrow(UUID id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new TenantNotFoundException(id));
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
        assertActorBelongsToTenant(actor, targetUser.getTenant());
    }

    private void assertActorBelongsToTenant(User actor, Tenant tenant) {
        if (!actor.getTenant().getId().equals(tenant.getId())) {
            throw new ForbiddenActionException(
                    "Actor " + actor.getId() + " cannot act on tenant " + tenant.getId());
        }
    }

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
