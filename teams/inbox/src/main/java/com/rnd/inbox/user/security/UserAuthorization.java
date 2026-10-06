package com.rnd.inbox.user.security;
import com.rnd.inbox.user.model.User;
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
}
