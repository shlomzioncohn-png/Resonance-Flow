package com.rnd.inbox.seedgen.generator.assignment;
import com.rnd.inbox.seedgen.generator.shared.RandomUtil;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.user.UserSeed;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;
import com.rnd.inbox.seedgen.generator.team.TeamSeed;

import com.rnd.inbox.seedgen.config.DatasetConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Distributes every tenant's {@code assignments} rows (design doc §4) across its users and
 * teams, via three per-tenant parameters drawn in sequence: sharing rate (how many rows exist
 * at all — a task with more than one assignee costs an extra row), team-assignment rate (how
 * those rows split between {@code team_id} and {@code user_id}), and concentration (how each of
 * those two pools spreads across the tenant's users/teams — {@link PowerLawAllocator}, the same
 * value for both pools so a tenant's shape is consistent whichever pool a row lands in).
 *
 * <p>"user-targeted" means assigned to one named person, not "belongs to a team" — most
 * user-targeted rows go to affiliated employees (the common case: a task assigned to someone who
 * also happens to be on a team), but the unaffiliated share (design doc §5) is eligible too, on
 * the same terms. Every one of the tenant's users, affiliated or not, is a candidate.
 */
public final class AssignmentGenerator {

    private AssignmentGenerator() {
    }

    public static AssignmentLoads generate(List<TenantSeed> tenants, List<TeamSeed> teams,
            List<UserSeed> users, Random random) {
        Map<TenantKey, List<UserSeed>> usersByTenant = users.stream()
                .collect(Collectors.groupingBy(UserSeed::tenantKey));

        Map<TenantKey, List<TeamSeed>> teamsByTenant = teams.stream()
                .collect(Collectors.groupingBy(TeamSeed::tenantKey));

        List<UserAssignmentLoad> userLoads = new ArrayList<>();
        List<TeamAssignmentLoad> teamLoads = new ArrayList<>();

        for (TenantSeed tenant : tenants) {
            List<UserSeed> tenantUsers = usersByTenant.getOrDefault(tenant.tenantKey(), List.of());
            List<TeamSeed> tenantTeams = teamsByTenant.getOrDefault(tenant.tenantKey(), List.of());

            double sharingRate = RandomUtil.inRange(random, DatasetConfig.SHARING_RATE);
            double teamAssignmentRate = RandomUtil.inRange(random, DatasetConfig.TEAM_ASSIGNMENT_RATE);
            double concentration = RandomUtil.inRange(random, DatasetConfig.CONCENTRATION);

            int sharedTaskCount = (int) Math.round(tenant.taskCount() * sharingRate);
            int totalRows = tenant.taskCount() + sharedTaskCount;

            int teamRows = tenantTeams.isEmpty() ? 0 : (int) Math.round(totalRows * teamAssignmentRate);
            if (tenantUsers.isEmpty()) {
                teamRows = totalRows;
            }
            int userRows = totalRows - teamRows;

            if (!tenantUsers.isEmpty()) {
                int[] shares = PowerLawAllocator.allocate(userRows, tenantUsers.size(), concentration);
                for (int i = 0; i < tenantUsers.size(); i++) {
                    UserSeed user = tenantUsers.get(i);
                    userLoads.add(new UserAssignmentLoad(
                            tenant.tierName(), tenant.tierIndex(), user.userIndexInTenant(), shares[i]));
                }
            }
            if (!tenantTeams.isEmpty()) {
                int[] shares = PowerLawAllocator.allocate(teamRows, tenantTeams.size(), concentration);
                for (int i = 0; i < tenantTeams.size(); i++) {
                    TeamSeed team = tenantTeams.get(i);
                    teamLoads.add(new TeamAssignmentLoad(
                            tenant.tierName(), tenant.tierIndex(), team.teamIndex(), shares[i]));
                }
            }
        }

        return new AssignmentLoads(List.copyOf(userLoads), List.copyOf(teamLoads));
    }
}
