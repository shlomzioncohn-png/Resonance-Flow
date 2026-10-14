package com.rnd.inbox.team.security;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.shared.security.ActorScope;
import com.rnd.inbox.shared.security.AdminOrTenantScopePolicy;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;
import org.springframework.stereotype.Component;

@Component
public class TeamAuthorization {

    private final AdminOrTenantScopePolicy<Team> delegate =
            new AdminOrTenantScopePolicy<>(t -> t.getTenant().getId(), Team::getId);

    public ActorScope scopeOf(User actor) {
        return delegate.scopeOf(actor);
    }

    public void assertWithinScope(ActorScope scope, Team target) {
        delegate.assertWithinScope(scope, target);
    }

    public void assertCanCreateIn(ActorScope scope, Tenant tenant) {
        delegate.assertCanCreateIn(scope, tenant);
    }
}
