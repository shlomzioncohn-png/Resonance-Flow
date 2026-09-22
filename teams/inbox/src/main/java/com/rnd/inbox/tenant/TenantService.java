package com.rnd.inbox.tenant;

import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.user.User;
import com.rnd.inbox.user.UserRepository;
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
public class TenantService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final TenantMapper tenantMapper;
    private final AuditService auditService;
    private final TenantAuthorization tenantAuthorization;

    public TenantService(TenantRepository tenantRepository, UserRepository userRepository,
                          TenantMapper tenantMapper, AuditService auditService,
                          TenantAuthorization tenantAuthorization) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.tenantMapper = tenantMapper;
        this.auditService = auditService;
        this.tenantAuthorization = tenantAuthorization;
    }

    // Gated by @RequireRole(SYSTEM_ADMIN) alone at the controller - creating a tenant has no
    // "destination" to scope against (unlike creating a User inside an existing tenant), so
    // there is no fine-grained check for TenantAuthorization to add here.
    @Transactional
    public TenantResponseDto createTenant(TenantCreateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        assertSlugAvailable(request.getSlug());

        Tenant tenant = tenantMapper.toEntity(request);
        Tenant saved = saveTenant(tenant);
        auditService.createAuditLog("TENANT_CREATED", "Tenant", saved.getId(), saved,
                actor, auditSnapshot(saved), null);
        return tenantMapper.toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public TenantResponseDto getTenantById(UUID id, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Tenant tenant = findTenantOrThrow(id);
        tenantAuthorization.assertWithinScope(tenantAuthorization.scopeOf(actor), tenant);
        return tenantMapper.toResponseDto(tenant);
    }

    // Gated by @RequireRole(SYSTEM_ADMIN) alone at the controller - every SYSTEM_ADMIN sees the
    // exact same full list, so there is no per-actor scoping to apply.
    @Transactional(readOnly = true)
    public PageResponseDto<TenantResponseDto> listTenants(Pageable pageable) {
        Page<Tenant> tenants = tenantRepository.findAll(pageable);
        return PageResponseDto.from(tenants.map(tenantMapper::toResponseDto));
    }

    @Transactional
    public TenantResponseDto updateTenant(UUID id, TenantUpdateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Tenant tenant = findTenantOrThrow(id);
        tenantAuthorization.assertWithinScope(tenantAuthorization.scopeOf(actor), tenant);

        tenantMapper.applyUpdate(tenant, request);
        Tenant saved = tenantRepository.save(tenant);
        auditService.createAuditLog("TENANT_UPDATED", "Tenant", saved.getId(), saved,
                actor, auditSnapshot(saved), null);
        return tenantMapper.toResponseDto(saved);
    }

    // Gated by @RequireRole(SYSTEM_ADMIN) alone at the controller - SYSTEM_ADMIN's scope is
    // always ActorScope.All, so a TenantAuthorization check here would always pass trivially.
    @Transactional
    public TenantResponseDto updateStatus(UUID id, TenantStatusUpdateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Tenant tenant = findTenantOrThrow(id);

        tenantMapper.applyStatusChange(tenant, TenantStatus.valueOf(request.getStatus()));
        Tenant saved = tenantRepository.save(tenant);
        auditService.createAuditLog("TENANT_STATUS_UPDATED", "Tenant", saved.getId(), saved,
                actor, auditSnapshot(saved), null);
        return tenantMapper.toResponseDto(saved);
    }

    private Tenant findTenantOrThrow(UUID id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new TenantNotFoundException(id));
    }

    private User findActorOrThrow(UUID actorId) {
        return userRepository.findById(actorId)
                .orElseThrow(() -> new UnauthenticatedException("Actor " + actorId + " does not exist"));
    }

    private Map<String, Object> auditSnapshot(Tenant tenant) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", tenant.getName());
        snapshot.put("slug", tenant.getSlug());
        snapshot.put("status", tenant.getStatus().name());
        snapshot.put("timezone", tenant.getTimezone());
        snapshot.put("localeDefault", tenant.getLocaleDefault());
        return snapshot;
    }

    private Tenant saveTenant(Tenant tenant) {
        try {
            return tenantRepository.save(tenant);
        } catch (DataIntegrityViolationException e) {
            throw translateUniqueConstraintViolation(tenant, e);
        }
    }

    private RuntimeException translateUniqueConstraintViolation(Tenant tenant, DataIntegrityViolationException e) {
        String constraintName = extractConstraintName(e);
        if ("uq_tenants_slug".equals(constraintName)) {
            return new DuplicateSlugException(tenant.getSlug());
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

    // No excludingTenantId parameter (unlike User's assertEmailAvailable/assertUsernameAvailable):
    // this is only ever called from createTenant, on a not-yet-saved Tenant, so a match can never
    // legitimately be "itself" - any match at all is a real conflict.
    private void assertSlugAvailable(String slug) {
        if (tenantRepository.findBySlug(slug).isPresent()) {
            throw new DuplicateSlugException(slug);
        }
    }
}
