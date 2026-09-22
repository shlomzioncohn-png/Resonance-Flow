package com.rnd.inbox.shared.security;

import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;
import java.util.UUID;

/**
 * Describes the boundary an acting {@code User}'s role limits them to, independently of which
 * entity is being acted on — every variant carries only data read off the actor's own row
 * (their tenant id, their own id), never anything about the target entity. Every
 * {@code {Entity}Authorization} shares this same vocabulary for {@code scopeOf(actor)}; how a
 * scope is checked against a specific target ({@code assertWithinScope}/{@code assertCanCreateIn})
 * still lives per-entity, since that depends on the target's own shape (e.g. a {@code User}'s
 * tenant is {@code target.getTenant().getId()}, while a {@code Tenant} target checks its own id
 * directly).
 */
public sealed interface ActorScope {

    record All() implements ActorScope {
    }

    record WithinTenant(UUID tenantId) implements ActorScope {
    }

    record WithinTeamsLedBy(UUID leaderId) implements ActorScope {
    }

    record SelfOnly(UUID userId) implements ActorScope {
    }
}
