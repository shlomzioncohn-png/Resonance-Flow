package com.rnd.inbox.team.security;
import com.rnd.inbox.team.model.Team;
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

class TeamAuthorizationTest {

    private TeamAuthorization teamAuthorization;

    private UUID tenantId;
    private Tenant tenant;
    private UUID actorId;
    private User actor;
    private Team team;

    @BeforeEach
    void setUp() {
        teamAuthorization = new TeamAuthorization();

        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);

        actorId = UUID.randomUUID();
        actor = new User();
        actor.setId(actorId);
        actor.setTenant(tenant);

        team = new Team();
        team.setId(UUID.randomUUID());
        team.setTenant(tenant);
    }

    @Test
    void scopeOf_whenSystemAdmin_returnsAll() {
        actor.setRole(UserRole.SYSTEM_ADMIN);

        assertThat(teamAuthorization.scopeOf(actor)).isEqualTo(new ActorScope.All());
    }

    @Test
    void scopeOf_whenEmployee_returnsWithinTenantForActorsOwnTenant() {
        actor.setRole(UserRole.EMPLOYEE);

        assertThat(teamAuthorization.scopeOf(actor)).isEqualTo(new ActorScope.WithinTenant(tenantId));
    }

    @Test
    void assertWithinScope_withinTenant_whenTeamIsInAnotherTenant_throwsForbiddenActionException() {
        Tenant otherTenant = new Tenant();
        otherTenant.setId(UUID.randomUUID());
        team.setTenant(otherTenant);

        assertThatThrownBy(() ->
                teamAuthorization.assertWithinScope(new ActorScope.WithinTenant(tenantId), team))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void assertWithinScope_withinTenant_whenSameTenant_doesNotThrow() {
        assertThatNoException().isThrownBy(() ->
                teamAuthorization.assertWithinScope(new ActorScope.WithinTenant(tenantId), team));
    }

    @Test
    void assertCanCreateIn_withinTenant_whenDifferentTenant_throwsForbiddenActionException() {
        Tenant otherTenant = new Tenant();
        otherTenant.setId(UUID.randomUUID());

        assertThatThrownBy(() ->
                teamAuthorization.assertCanCreateIn(new ActorScope.WithinTenant(tenantId), otherTenant))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void assertCanCreateIn_all_alwaysAllowed() {
        assertThatNoException()
                .isThrownBy(() -> teamAuthorization.assertCanCreateIn(new ActorScope.All(), tenant));
    }
}
