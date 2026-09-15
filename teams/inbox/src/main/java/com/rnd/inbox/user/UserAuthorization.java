package com.rnd.inbox.user;

import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.tenant.Tenant;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UserAuthorization {

    private final UserRepository userRepository;

    public UserAuthorization(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserScope scopeOf(User actor) {
        return switch (actor.getRole()) {
            case SYSTEM_ADMIN -> new UserScope.All();
            case TENANT_ADMIN -> new UserScope.WithinTenant(actor.getTenant().getId());
            case TEAM_LEAD -> new UserScope.WithinTeamsLedBy(actor.getId());
            case EMPLOYEE -> new UserScope.SelfOnly(actor.getId());
        };
    }

    public void assertWithinScope(UserScope scope, User target) {
        switch (scope) {
            case UserScope.All ignored -> {
            }
            case UserScope.WithinTenant(UUID tenantId) -> {
                if (!target.getTenant().getId().equals(tenantId)) {
                    throw new ForbiddenActionException(
                            "User " + target.getId() + " does not belong to tenant " + tenantId);
                }
            }
            case UserScope.WithinTeamsLedBy(UUID leaderId) -> {
                if (!userRepository.existsInTeamLedBy(target.getId(), leaderId)) {
                    throw new ForbiddenActionException(
                            "User " + target.getId() + " is not on a team led by " + leaderId);
                }
            }
            case UserScope.SelfOnly(UUID userId) -> {
                if (!target.getId().equals(userId)) {
                    throw new ForbiddenActionException(
                            "Actor " + userId + " can only act on themselves, not user " + target.getId());
                }
            }
        }
    }

    public void assertCanCreateIn(UserScope scope, Tenant tenant) {
        boolean allowed = switch (scope) {
            case UserScope.All ignored -> true;
            case UserScope.WithinTenant(UUID tenantId) -> tenantId.equals(tenant.getId());
            case UserScope.WithinTeamsLedBy ignored -> false;
            case UserScope.SelfOnly ignored -> false;
        };
        if (!allowed) {
            throw new ForbiddenActionException("Actor cannot create users in tenant " + tenant.getId());
        }
    }
}
