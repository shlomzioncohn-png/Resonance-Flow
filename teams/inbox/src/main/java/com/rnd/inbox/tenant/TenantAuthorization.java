package com.rnd.inbox.tenant;

import com.rnd.inbox.shared.security.ActorScope;
import com.rnd.inbox.shared.security.AdminOrTenantScopePolicy;
import com.rnd.inbox.user.User;
import org.springframework.stereotype.Component;

@Component
public class TenantAuthorization {

    // A Tenant target's own id IS its tenant id - it has no separate tenant_id column, it is the
    // tenant - hence the identity lambda instead of t -> t.getTenant().getId().
    private final AdminOrTenantScopePolicy<Tenant> delegate =
            new AdminOrTenantScopePolicy<>(Tenant::getId, Tenant::getId);

    public ActorScope scopeOf(User actor) {
        return delegate.scopeOf(actor);
    }

    public void assertWithinScope(ActorScope scope, Tenant target) {
        delegate.assertWithinScope(scope, target);
    }
}
