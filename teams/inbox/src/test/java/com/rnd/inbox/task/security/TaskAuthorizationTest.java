package com.rnd.inbox.task.security;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.security.ActorScope;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskAuthorizationTest {

    private TaskAuthorization taskAuthorization;

    private UUID tenantId;
    private Tenant tenant;
    private UUID actorId;
    private User actor;
    private Task task;

    @BeforeEach
    void setUp() {
        taskAuthorization = new TaskAuthorization();

        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);

        actorId = UUID.randomUUID();
        actor = new User();
        actor.setId(actorId);
        actor.setTenant(tenant);

        task = new Task();
        task.setId(UUID.randomUUID());
        task.setTenant(tenant);
    }

    @Test
    void scopeOf_whenSystemAdmin_returnsAll() {
        actor.setRole(UserRole.SYSTEM_ADMIN);

        assertThat(taskAuthorization.scopeOf(actor)).isEqualTo(new ActorScope.All());
    }

    @Test
    void scopeOf_whenTenantAdmin_returnsWithinTenantForActorsOwnTenant() {
        actor.setRole(UserRole.TENANT_ADMIN);

        assertThat(taskAuthorization.scopeOf(actor)).isEqualTo(new ActorScope.WithinTenant(tenantId));
    }

    @Test
    void scopeOf_whenTeamLead_returnsWithinTenantForActorsOwnTenant() {
        actor.setRole(UserRole.TEAM_LEAD);

        assertThat(taskAuthorization.scopeOf(actor)).isEqualTo(new ActorScope.WithinTenant(tenantId));
    }

    @Test
    void scopeOf_whenEmployee_returnsWithinTenantForActorsOwnTenant() {
        actor.setRole(UserRole.EMPLOYEE);

        assertThat(taskAuthorization.scopeOf(actor)).isEqualTo(new ActorScope.WithinTenant(tenantId));
    }

    @Test
    void assertWithinScope_all_neverThrows() {
        assertThatNoException()
                .isThrownBy(() -> taskAuthorization.assertWithinScope(new ActorScope.All(), task));
    }

    @Test
    void assertWithinScope_withinTenant_whenTaskIsInActorsTenant_doesNotThrow() {
        assertThatNoException().isThrownBy(() ->
                taskAuthorization.assertWithinScope(new ActorScope.WithinTenant(tenantId), task));
    }

    @Test
    void assertWithinScope_withinTenant_whenTaskIsInAnotherTenant_throwsForbiddenActionException() {
        Tenant otherTenant = new Tenant();
        otherTenant.setId(UUID.randomUUID());
        task.setTenant(otherTenant);

        assertThatThrownBy(() ->
                taskAuthorization.assertWithinScope(new ActorScope.WithinTenant(tenantId), task))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void assertCanCreateIn_all_alwaysAllowed() {
        assertThatNoException()
                .isThrownBy(() -> taskAuthorization.assertCanCreateIn(new ActorScope.All(), tenant));
    }

    @Test
    void assertCanCreateIn_withinTenant_whenSameTenant_doesNotThrow() {
        assertThatNoException().isThrownBy(() ->
                taskAuthorization.assertCanCreateIn(new ActorScope.WithinTenant(tenantId), tenant));
    }

    @Test
    void assertCanCreateIn_withinTenant_whenDifferentTenant_throwsForbiddenActionException() {
        Tenant otherTenant = new Tenant();
        otherTenant.setId(UUID.randomUUID());

        assertThatThrownBy(() ->
                taskAuthorization.assertCanCreateIn(new ActorScope.WithinTenant(tenantId), otherTenant))
                .isInstanceOf(ForbiddenActionException.class);
    }
}
