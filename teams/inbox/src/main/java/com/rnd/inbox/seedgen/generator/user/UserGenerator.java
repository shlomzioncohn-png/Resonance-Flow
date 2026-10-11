package com.rnd.inbox.seedgen.generator.user;
import com.rnd.inbox.seedgen.generator.shared.AffiliationSplit;
import com.rnd.inbox.seedgen.generator.team.TeamGenerator;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;
import com.rnd.inbox.seedgen.generator.team.TeamSeed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Places every tenant's users into the teams {@link TeamGenerator} already built, plus the
 * unaffiliated share (design doc §5). Deterministic and random-free: which user is a team lead
 * ("the first member of the team") and how many users are unaffiliated (already fixed by
 * {@link AffiliationSplit}) never depend on a random draw.
 */
public final class UserGenerator {

    private UserGenerator() {
    }

    /**
     * Generates every user for every tenant, in tenant list order. For each tenant: its teams
     * (in team-index order) are filled member by member — the first member of each team is that
     * team's lead — then the tenant's unaffiliated share (§5) is appended with no team at all.
     * {@code userIndexInTenant} runs 0..(tenant.userCount() - 1) with no gaps across both parts.
     */
    public static List<UserSeed> generate(List<TenantSeed> tenants, List<TeamSeed> teams) {
        Map<TenantKey, List<TeamSeed>> teamsByTenant = teams.stream()
                .collect(Collectors.groupingBy(TeamSeed::tenantKey));

        List<UserSeed> users = new ArrayList<>();
        for (TenantSeed tenant : tenants) {
            List<TeamSeed> tenantTeams = teamsByTenant.getOrDefault(tenant.tenantKey(), List.of());
            int userIndex = 0;

            for (TeamSeed team : tenantTeams) {
                for (int member = 0; member < team.size(); member++) {
                    boolean isLead = member == 0;
                    users.add(new UserSeed(
                            tenant.tierName(), tenant.tierIndex(), team.teamIndex(), userIndex, isLead));
                    userIndex++;
                }
            }

            int unaffiliated = AffiliationSplit.of(tenant.userCount()).unaffiliatedCount();
            for (int i = 0; i < unaffiliated; i++) {
                users.add(new UserSeed(tenant.tierName(), tenant.tierIndex(), null, userIndex, false));
                userIndex++;
            }
        }
        return List.copyOf(users);
    }
}
