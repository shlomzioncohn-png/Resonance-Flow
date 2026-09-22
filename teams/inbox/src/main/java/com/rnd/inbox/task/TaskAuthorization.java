package com.rnd.inbox.task;

import com.rnd.inbox.shared.security.ActorScope;
import com.rnd.inbox.shared.security.AdminOrTenantScopePolicy;
import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.user.User;
import org.springframework.stereotype.Component;

@Component
public class TaskAuthorization {

    private final AdminOrTenantScopePolicy<Task> delegate =
            new AdminOrTenantScopePolicy<>(t -> t.getTenant().getId(), Task::getId);

    public ActorScope scopeOf(User actor) {
        return delegate.scopeOf(actor);
    }

    public void assertWithinScope(ActorScope scope, Task target) {
        delegate.assertWithinScope(scope, target);
    }

    public void assertCanCreateIn(ActorScope scope, Tenant tenant) {
        delegate.assertCanCreateIn(scope, tenant);
    }
}
