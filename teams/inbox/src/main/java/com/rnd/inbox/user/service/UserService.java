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
import com.rnd.inbox.user.model.UserStatus;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.user.security.UserAuthorization;
import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.shared.security.ActorScope;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.exception.TenantNotFoundException;
import com.rnd.inbox.tenant.repository.TenantRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final UserAuthorization userAuthorization;

    public UserService(UserRepository userRepository, TenantRepository tenantRepository,
                        UserMapper userMapper, AuditService auditService, UserAuthorization userAuthorization) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.userMapper = userMapper;
        this.auditService = auditService;
        this.userAuthorization = userAuthorization;
    }

    @Transactional
    public UserResponseDto createUser(UserCreateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Tenant tenant = findTenantOrThrow(UUID.fromString(request.getTenantId()));
        userAuthorization.assertCanCreateIn(userAuthorization.scopeOf(actor), tenant);
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
        userAuthorization.assertWithinScope(userAuthorization.scopeOf(actor), user);
        return userMapper.toResponseDto(user);
    }

    @Transactional
    public UserResponseDto updateUser(UUID id, UserUpdateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        User user = findUserOrThrow(id);
        userAuthorization.assertWithinScope(userAuthorization.scopeOf(actor), user);
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
        Page<User> scopedUsers = switch (userAuthorization.scopeOf(actor)) {
            case ActorScope.All ignored -> userRepository.findAll(pageable);
            case ActorScope.WithinTenant(UUID tenantId) -> userRepository.findAllByTenantId(tenantId, pageable);
            case ActorScope.WithinTeamsLedBy(UUID leaderId) -> userRepository.findAllByTeamLeadId(leaderId, pageable);
            case ActorScope.SelfOnly ignored -> selfOnlyPage(actor, pageable);
        };
        return PageResponseDto.from(scopedUsers.map(userMapper::toResponseDto));
    }

    @Transactional
    public UserResponseDto updateStatus(UUID id, UserStatusUpdateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        User user = findUserOrThrow(id);
        userAuthorization.assertWithinScope(userAuthorization.scopeOf(actor), user);

        userMapper.applyStatusChange(user, UserStatus.valueOf(request.getStatus()));
        User saved = userRepository.save(user);
        auditService.createAuditLog("USER_STATUS_UPDATED", "User", saved.getId(), saved.getTenant(),
                actor, auditSnapshot(saved), null);
        return userMapper.toResponseDto(saved);
    }

    private Page<User> selfOnlyPage(User actor, Pageable pageable) {
        List<User> content = pageable.getOffset() == 0 ? List.of(actor) : List.of();
        return new PageImpl<>(content, pageable, 1);
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
