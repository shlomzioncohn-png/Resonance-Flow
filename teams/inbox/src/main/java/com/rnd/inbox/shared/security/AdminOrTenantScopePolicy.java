package com.rnd.inbox.shared.security;

import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.security.TaskAuthorization;
import com.rnd.inbox.tenant.security.TenantAuthorization;
import com.rnd.inbox.user.security.UserAuthorization;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;

import java.util.UUID;
import java.util.function.Function;

/**
 * Shared {@code {Entity}Authorization} logic for entities whose access rule is the simplest
 * possible one: {@code SYSTEM_ADMIN} touches everything, every other role is limited to their own
 * tenant, with no finer split (no team-lead or self-only distinction on top). Extracted after
 * {@code TenantAuthorization} and {@code TaskAuthorization} turned out byte-for-byte identical
 * except for the target type - a real second copy-paste, not a premature abstraction.
 * <p>
 * Entities with richer rules (e.g. {@code UserAuthorization}, where {@code TEAM_LEAD} and
 * {@code EMPLOYEE} get their own distinct {@link ActorScope} variants) do <b>not</b> use this -
 * forcing that logic through here would leak entity-specific branches into shared code. Use this
 * only when a new entity's Q6 answer is genuinely "SYSTEM_ADMIN vs. everyone else's own tenant,
 * nothing finer."
 *
 * @param <T> the target entity type (e.g. {@code Task}, {@code Tenant})
 */
public final class AdminOrTenantScopePolicy<T> {

    private final Function<T, UUID> tenantIdOf;
    private final Function<T, Object> labelOf;

    /**
     * @param tenantIdOf how to read the tenant id off a target instance (e.g.
     *                   {@code t -> t.getTenant().getId()})
     * @param labelOf    how to identify the target in a {@link ForbiddenActionException} message
     *                   (typically {@code T::getId})
     */
    public AdminOrTenantScopePolicy(Function<T, UUID> tenantIdOf, Function<T, Object> labelOf) {
        this.tenantIdOf = tenantIdOf;
        this.labelOf = labelOf;
    }

    public ActorScope scopeOf(User actor) {
        return switch (actor.getRole()) {
            case SYSTEM_ADMIN -> new ActorScope.All();
            case TENANT_ADMIN, TEAM_LEAD, EMPLOYEE -> new ActorScope.WithinTenant(actor.getTenant().getId());
        };
    }

    public void assertWithinScope(ActorScope scope, T target) {
        switch (scope) {
            case ActorScope.All ignored -> {
            }
            case ActorScope.WithinTenant(UUID tenantId) -> {
                if (!tenantIdOf.apply(target).equals(tenantId)) {
                    throw new ForbiddenActionException("Actor cannot act on " + labelOf.apply(target));
                }
            }
            case ActorScope.WithinTeamsLedBy ignored -> throw new ForbiddenActionException(
                    "Actor cannot act on " + labelOf.apply(target));
            case ActorScope.SelfOnly ignored -> throw new ForbiddenActionException(
                    "Actor cannot act on " + labelOf.apply(target));
        }
    }

    public void assertCanCreateIn(ActorScope scope, Tenant tenant) {
        boolean allowed = switch (scope) {
            case ActorScope.All ignored -> true;
            case ActorScope.WithinTenant(UUID tenantId) -> tenantId.equals(tenant.getId());
            case ActorScope.WithinTeamsLedBy ignored -> false;
            case ActorScope.SelfOnly ignored -> false;
        };
        if (!allowed) {
            throw new ForbiddenActionException("Actor cannot create in tenant " + tenant.getId());
        }
    }
}
