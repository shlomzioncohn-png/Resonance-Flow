package com.rnd.inbox.user.security;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.shared.security.ActorScope;
import com.rnd.inbox.tenant.model.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAuthorizationTest {

    @Mock
    private UserRepository userRepository;

    private UserAuthorization userAuthorization;

    private UUID tenantId;
    private Tenant tenant;
    private UUID actorId;
    private User actor;
    private UUID targetId;
    private User target;

    @BeforeEach
    void setUp() {
        userAuthorization = new UserAuthorization(userRepository);

        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);

        actorId = UUID.randomUUID();
        actor = new User();
        actor.setId(actorId);
        actor.setTenant(tenant);

        targetId = UUID.randomUUID();
        target = new User();
        target.setId(targetId);
        target.setTenant(tenant);
    }

    @Test
    void scopeOf_whenSystemAdmin_returnsAll() {
        actor.setRole(UserRole.SYSTEM_ADMIN);

        assertThat(userAuthorization.scopeOf(actor)).isEqualTo(new ActorScope.All());
    }

    @Test
    void scopeOf_whenTenantAdmin_returnsWithinTenantForActorsOwnTenant() {
        actor.setRole(UserRole.TENANT_ADMIN);

        assertThat(userAuthorization.scopeOf(actor)).isEqualTo(new ActorScope.WithinTenant(tenantId));
    }

    @Test
    void scopeOf_whenTeamLead_returnsWithinTeamsLedByActor() {
        actor.setRole(UserRole.TEAM_LEAD);

        assertThat(userAuthorization.scopeOf(actor)).isEqualTo(new ActorScope.WithinTeamsLedBy(actorId));
    }

    @Test
    void scopeOf_whenEmployee_returnsSelfOnly() {
        actor.setRole(UserRole.EMPLOYEE);

        assertThat(userAuthorization.scopeOf(actor)).isEqualTo(new ActorScope.SelfOnly(actorId));
    }

    @Test
    void assertWithinScope_all_neverThrows() {
        assertThatNoException()
                .isThrownBy(() -> userAuthorization.assertWithinScope(new ActorScope.All(), target));
    }

    @Test
    void assertWithinScope_withinTenant_whenTargetInSameTenant_doesNotThrow() {
        assertThatNoException().isThrownBy(() ->
                userAuthorization.assertWithinScope(new ActorScope.WithinTenant(tenantId), target));
    }

    @Test
    void assertWithinScope_withinTenant_whenTargetInDifferentTenant_throwsForbiddenActionException() {
        Tenant otherTenant = new Tenant();
        otherTenant.setId(UUID.randomUUID());
        target.setTenant(otherTenant);

        assertThatThrownBy(() -> userAuthorization.assertWithinScope(new ActorScope.WithinTenant(tenantId), target))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void assertWithinScope_withinTeamsLedBy_whenTargetOnLeaderTeam_doesNotThrow() {
        when(userRepository.existsInTeamLedBy(targetId, actorId)).thenReturn(true);

        assertThatNoException().isThrownBy(() ->
                userAuthorization.assertWithinScope(new ActorScope.WithinTeamsLedBy(actorId), target));
    }

    @Test
    void assertWithinScope_withinTeamsLedBy_whenTargetNotOnLeaderTeam_throwsForbiddenActionException() {
        when(userRepository.existsInTeamLedBy(targetId, actorId)).thenReturn(false);

        assertThatThrownBy(() ->
                userAuthorization.assertWithinScope(new ActorScope.WithinTeamsLedBy(actorId), target))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void assertWithinScope_selfOnly_whenTargetIsSelf_doesNotThrow() {
        assertThatNoException().isThrownBy(() ->
                userAuthorization.assertWithinScope(new ActorScope.SelfOnly(targetId), target));
    }

    @Test
    void assertWithinScope_selfOnly_whenTargetIsSomeoneElse_throwsForbiddenActionException() {
        assertThatThrownBy(() ->
                userAuthorization.assertWithinScope(new ActorScope.SelfOnly(actorId), target))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void assertCanCreateIn_all_alwaysAllowed() {
        assertThatNoException()
                .isThrownBy(() -> userAuthorization.assertCanCreateIn(new ActorScope.All(), tenant));
    }

    @Test
    void assertCanCreateIn_withinTenant_whenSameTenant_doesNotThrow() {
        assertThatNoException().isThrownBy(() ->
                userAuthorization.assertCanCreateIn(new ActorScope.WithinTenant(tenantId), tenant));
    }

    @Test
    void assertCanCreateIn_withinTenant_whenDifferentTenant_throwsForbiddenActionException() {
        Tenant otherTenant = new Tenant();
        otherTenant.setId(UUID.randomUUID());

        assertThatThrownBy(() ->
                userAuthorization.assertCanCreateIn(new ActorScope.WithinTenant(tenantId), otherTenant))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void assertCanCreateIn_withinTeamsLedBy_alwaysForbidden() {
        assertThatThrownBy(() ->
                userAuthorization.assertCanCreateIn(new ActorScope.WithinTeamsLedBy(actorId), tenant))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void assertCanCreateIn_selfOnly_alwaysForbidden() {
        assertThatThrownBy(() ->
                userAuthorization.assertCanCreateIn(new ActorScope.SelfOnly(actorId), tenant))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "SYSTEM_ADMIN, SYSTEM_ADMIN, true",
            "SYSTEM_ADMIN, TENANT_ADMIN, true",
            "SYSTEM_ADMIN, TEAM_LEAD, true",
            "SYSTEM_ADMIN, EMPLOYEE, true",
            "TENANT_ADMIN, SYSTEM_ADMIN, false",
            "TENANT_ADMIN, TENANT_ADMIN, true",
            "TENANT_ADMIN, TEAM_LEAD, true",
            "TENANT_ADMIN, EMPLOYEE, true",
            "TEAM_LEAD, SYSTEM_ADMIN, false",
            "TEAM_LEAD, TENANT_ADMIN, false",
            "TEAM_LEAD, TEAM_LEAD, false",
            "TEAM_LEAD, EMPLOYEE, false",
            "EMPLOYEE, SYSTEM_ADMIN, false",
            "EMPLOYEE, TENANT_ADMIN, false",
            "EMPLOYEE, TEAM_LEAD, false",
            "EMPLOYEE, EMPLOYEE, false"
    })
    void assertCanAssignRole_allowsOnlyRolesTheActorMayGrant(UserRole actorRole, UserRole role, boolean allowed) {
        actor.setRole(actorRole);

        if (allowed) {
            assertThatNoException().isThrownBy(() -> userAuthorization.assertCanAssignRole(actor, role));
        } else {
            assertThatThrownBy(() -> userAuthorization.assertCanAssignRole(actor, role))
                    .isInstanceOf(ForbiddenActionException.class);
        }
    }

    @Test
    void assertCanChangeRole_whenRoleIsUnchanged_neverThrowsEvenForEmployee() {
        actor.setRole(UserRole.EMPLOYEE);
        target.setRole(UserRole.EMPLOYEE);

        assertThatNoException().isThrownBy(
                () -> userAuthorization.assertCanChangeRole(actor, target, UserRole.EMPLOYEE));
    }

    @Test
    void assertCanChangeRole_whenEmployeeChangesRole_throwsForbiddenActionException() {
        actor.setRole(UserRole.EMPLOYEE);
        target.setRole(UserRole.EMPLOYEE);

        assertThatThrownBy(() -> userAuthorization.assertCanChangeRole(actor, target, UserRole.TEAM_LEAD))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void assertCanChangeRole_whenTenantAdminDemotesSystemAdmin_throwsForbiddenActionException() {
        actor.setRole(UserRole.TENANT_ADMIN);
        target.setRole(UserRole.SYSTEM_ADMIN);

        assertThatThrownBy(() -> userAuthorization.assertCanChangeRole(actor, target, UserRole.EMPLOYEE))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void assertCanChangeRole_whenTenantAdminPromotesEmployeeToTenantAdmin_doesNotThrow() {
        actor.setRole(UserRole.TENANT_ADMIN);
        target.setRole(UserRole.EMPLOYEE);

        assertThatNoException().isThrownBy(
                () -> userAuthorization.assertCanChangeRole(actor, target, UserRole.TENANT_ADMIN));
    }
}
