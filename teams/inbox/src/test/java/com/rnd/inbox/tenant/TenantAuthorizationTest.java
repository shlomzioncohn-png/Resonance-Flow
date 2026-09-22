package com.rnd.inbox.tenant;

import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.security.ActorScope;
import com.rnd.inbox.user.User;
import com.rnd.inbox.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantAuthorizationTest {

    private TenantAuthorization tenantAuthorization;

    private UUID tenantId;
    private Tenant tenant;
    private UUID actorId;
    private User actor;

    @BeforeEach
    void setUp() {
        tenantAuthorization = new TenantAuthorization();

        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);

        actorId = UUID.randomUUID();
        actor = new User();
        actor.setId(actorId);
        actor.setTenant(tenant);
    }

    @Test
    void scopeOf_whenSystemAdmin_returnsAll() {
        actor.setRole(UserRole.SYSTEM_ADMIN);

        assertThat(tenantAuthorization.scopeOf(actor)).isEqualTo(new ActorScope.All());
    }

    @Test
    void scopeOf_whenTenantAdmin_returnsWithinTenantForActorsOwnTenant() {
        actor.setRole(UserRole.TENANT_ADMIN);

        assertThat(tenantAuthorization.scopeOf(actor)).isEqualTo(new ActorScope.WithinTenant(tenantId));
    }

    @Test
    void scopeOf_whenTeamLead_returnsWithinTenantForActorsOwnTenant() {
        actor.setRole(UserRole.TEAM_LEAD);

        assertThat(tenantAuthorization.scopeOf(actor)).isEqualTo(new ActorScope.WithinTenant(tenantId));
    }

    @Test
    void scopeOf_whenEmployee_returnsWithinTenantForActorsOwnTenant() {
        actor.setRole(UserRole.EMPLOYEE);

        assertThat(tenantAuthorization.scopeOf(actor)).isEqualTo(new ActorScope.WithinTenant(tenantId));
    }

    @Test
    void assertWithinScope_all_neverThrows() {
        assertThatNoException()
                .isThrownBy(() -> tenantAuthorization.assertWithinScope(new ActorScope.All(), tenant));
    }

    @Test
    void assertWithinScope_withinTenant_whenTargetIsActorsOwnTenant_doesNotThrow() {
        assertThatNoException().isThrownBy(() ->
                tenantAuthorization.assertWithinScope(new ActorScope.WithinTenant(tenantId), tenant));
    }

    @Test
    void assertWithinScope_withinTenant_whenTargetIsAnotherTenant_throwsForbiddenActionException() {
        Tenant otherTenant = new Tenant();
        otherTenant.setId(UUID.randomUUID());

        assertThatThrownBy(() ->
                tenantAuthorization.assertWithinScope(new ActorScope.WithinTenant(tenantId), otherTenant))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void assertWithinScope_withinTeamsLedBy_alwaysThrowsForbiddenActionException() {
        // scopeOf never produces this variant for Tenant - defensive branch, exercised directly.
        assertThatThrownBy(() ->
                tenantAuthorization.assertWithinScope(new ActorScope.WithinTeamsLedBy(actorId), tenant))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void assertWithinScope_selfOnly_alwaysThrowsForbiddenActionException() {
        // scopeOf never produces this variant for Tenant - defensive branch, exercised directly.
        assertThatThrownBy(() ->
                tenantAuthorization.assertWithinScope(new ActorScope.SelfOnly(actorId), tenant))
                .isInstanceOf(ForbiddenActionException.class);
    }
}
