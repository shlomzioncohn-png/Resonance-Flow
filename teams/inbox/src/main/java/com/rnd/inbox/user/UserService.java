package com.rnd.inbox.user;

import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.tenant.TenantNotFoundException;
import com.rnd.inbox.tenant.TenantRepository;
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
        assertEmailAvailable(request.getEmail(), null);
        assertUsernameAvailable(request.getUsername(), null);

        User user = userMapper.toEntity(request, tenant);
        User saved = userRepository.save(user);
        auditService.createAuditLog("USER_CREATED", "User", saved.getId(), tenant,
                actor, auditSnapshot(saved), null);
        return userMapper.toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public UserResponseDto getUserById(UUID id) {
        User user = findUserOrThrow(id);
        return userMapper.toResponseDto(user);
    }

    @Transactional
    public UserResponseDto updateUser(UUID id, UserUpdateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        User user = findUserOrThrow(id);
        assertEmailAvailable(request.getEmail(), id);
        assertUsernameAvailable(request.getUsername(), id);

        userMapper.applyUpdate(user, request);
        User saved = userRepository.save(user);
        auditService.createAuditLog("USER_UPDATED", "User", saved.getId(), saved.getTenant(),
                actor, auditSnapshot(saved), null);
        return userMapper.toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<UserResponseDto> listUsers(Pageable pageable) {
        Page<UserResponseDto> responsePage = userRepository.findAll(pageable).map(userMapper::toResponseDto);
        return PageResponseDto.from(responsePage);
    }

    @Transactional
    public UserResponseDto updateStatus(UUID id, UserStatusUpdateRequestDto request) {
        User user = findUserOrThrow(id);
        userMapper.applyStatusChange(user, UserStatus.valueOf(request.getStatus()));
        User saved = userRepository.save(user);
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
        snapshot.put("email", user.getEmail());
        snapshot.put("username", user.getUsername());
        snapshot.put("role", user.getRole());
        snapshot.put("status", user.getStatus().name());
        return snapshot;
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
