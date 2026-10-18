package com.rnd.inbox.project.security;

import com.rnd.inbox.project.Project;
import com.rnd.inbox.shared.security.ActorScope;
import com.rnd.inbox.shared.security.AdminOrTenantScopePolicy;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;
import org.springframework.stereotype.Component;

@Component
public class ProjectAuthorization {

    private final AdminOrTenantScopePolicy<Project> delegate =
            new AdminOrTenantScopePolicy<>(p -> p.getTenant().getId(), Project::getId);

    public ActorScope scopeOf(User actor) {
        return delegate.scopeOf(actor);
    }

    public void assertWithinScope(ActorScope scope, Project target) {
        delegate.assertWithinScope(scope, target);
    }

    public void assertCanCreateIn(ActorScope scope, Tenant tenant) {
        delegate.assertCanCreateIn(scope, tenant);
    }
}
