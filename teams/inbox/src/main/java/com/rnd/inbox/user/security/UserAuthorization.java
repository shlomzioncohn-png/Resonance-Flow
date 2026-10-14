package com.rnd.inbox.user.security;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.security.ActorScope;
import com.rnd.inbox.tenant.model.Tenant;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UserAuthorization {

    private final UserRepository userRepository;

    public UserAuthorization(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public ActorScope scopeOf(User actor) {
        return switch (actor.getRole()) {
            case SYSTEM_ADMIN -> new ActorScope.All();
            case TENANT_ADMIN -> new ActorScope.WithinTenant(actor.getTenant().getId());
            case TEAM_LEAD -> new ActorScope.WithinTeamsLedBy(actor.getId());
            case EMPLOYEE -> new ActorScope.SelfOnly(actor.getId());
        };
    }

    public void assertWithinScope(ActorScope scope, User target) {
        switch (scope) {
            case ActorScope.All ignored -> {
            }
            case ActorScope.WithinTenant(UUID tenantId) -> {
                if (!target.getTenant().getId().equals(tenantId)) {
                    throw new ForbiddenActionException(
                            "User " + target.getId() + " does not belong to tenant " + tenantId);
                }
            }
            case ActorScope.WithinTeamsLedBy(UUID leaderId) -> {
                if (!userRepository.existsInTeamLedBy(target.getId(), leaderId)) {
                    throw new ForbiddenActionException(
                            "User " + target.getId() + " is not on a team led by " + leaderId);
                }
            }
            case ActorScope.SelfOnly(UUID userId) -> {
                if (!target.getId().equals(userId)) {
                    throw new ForbiddenActionException(
                            "Actor " + userId + " can only act on themselves, not user " + target.getId());
                }
            }
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
            throw new ForbiddenActionException("Actor cannot create users in tenant " + tenant.getId());
        }
    }

    // scopeOf/assertWithinScope answer "WHICH users may this actor touch"; this answers a
    // different question they cannot: "which ROLE may this actor hand out". Without it, anyone
    // allowed to edit a user (an EMPLOYEE editing themselves, a TEAM_LEAD editing a team member)
    // could put any role in the request body, and a TENANT_ADMIN could mint a SYSTEM_ADMIN.
    // SYSTEM_ADMIN grants any role; TENANT_ADMIN grants every role except SYSTEM_ADMIN;
    // TEAM_LEAD and EMPLOYEE grant none.
    public void assertCanAssignRole(User actor, UserRole role) {
        if (!canAssignRole(actor.getRole(), role)) {
            throw new ForbiddenActionException(
                    "Role " + actor.getRole() + " is not permitted to assign role " + role);
        }
    }

    // Sending the role the target already has is not a change and is always allowed, so a client
    // that resends the whole profile on every edit isn't rejected. A real change needs the actor
    // to be allowed to grant BOTH the new role and the target's current one - otherwise a
    // TENANT_ADMIN could demote a SYSTEM_ADMIN who happens to sit in their tenant.
    public void assertCanChangeRole(User actor, User target, UserRole newRole) {
        if (newRole == target.getRole()) {
            return;
        }
        if (!canAssignRole(actor.getRole(), newRole) || !canAssignRole(actor.getRole(), target.getRole())) {
            throw new ForbiddenActionException(
                    "Role " + actor.getRole() + " is not permitted to change the role of user "
                            + target.getId() + " to " + newRole);
        }
    }

    private boolean canAssignRole(UserRole actorRole, UserRole role) {
        return switch (actorRole) {
            case SYSTEM_ADMIN -> true;
            case TENANT_ADMIN -> role != UserRole.SYSTEM_ADMIN;
            case TEAM_LEAD, EMPLOYEE -> false;
        };
    }
}
