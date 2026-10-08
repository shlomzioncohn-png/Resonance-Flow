package com.rnd.inbox.seedgen.generator.user;
import com.rnd.inbox.seedgen.generator.shared.AffiliationSplit;
import com.rnd.inbox.seedgen.generator.tenant.TenantGenerator;
import com.rnd.inbox.seedgen.generator.team.TeamGenerator;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;
import com.rnd.inbox.seedgen.generator.team.TeamSeed;

import com.rnd.inbox.seedgen.config.DatasetConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class UserGeneratorTest {

    /** (tenant, team) composite key for the "one lead per team" check — same reasoning as
     * {@link TenantKey} itself: a real type instead of a hand-built string. */
    private record TeamKey(TenantKey tenant, Integer teamIndex) {
    }

    @Test
    void generate_always_everyTenantsUserCountMatchesExactly() {
        List<TenantSeed> tenants = TenantGenerator.generate();
        List<TeamSeed> teams = TeamGenerator.generate(tenants, new Random(DatasetConfig.SEED));
        List<UserSeed> users = UserGenerator.generate(tenants, teams);

        Map<TenantKey, Long> userCountByTenant = users.stream()
                .collect(Collectors.groupingBy(UserSeed::tenantKey, Collectors.counting()));

        for (TenantSeed tenant : tenants) {
            assertThat(userCountByTenant.get(tenant.tenantKey())).isEqualTo((long) tenant.userCount());
        }
    }

    @Test
    void generate_always_userIndexInTenantIsGaplessFromZero() {
        List<TenantSeed> tenants = TenantGenerator.generate();
        List<TeamSeed> teams = TeamGenerator.generate(tenants, new Random(DatasetConfig.SEED));
        List<UserSeed> users = UserGenerator.generate(tenants, teams);

        Map<TenantKey, List<UserSeed>> byTenant = users.stream()
                .collect(Collectors.groupingBy(UserSeed::tenantKey));

        byTenant.forEach((key, tenantUsers) -> {
            List<Integer> indices = tenantUsers.stream()
                    .map(UserSeed::userIndexInTenant)
                    .sorted()
                    .toList();
            List<Integer> expected = IntStream.range(0, tenantUsers.size()).boxed().toList();

            assertThat(indices).as("user indices for tenant %s", key).isEqualTo(expected);
        });
    }

    @Test
    void generate_always_everyTeamHasExactlyOneLead() {
        List<TenantSeed> tenants = TenantGenerator.generate();
        List<TeamSeed> teams = TeamGenerator.generate(tenants, new Random(DatasetConfig.SEED));
        List<UserSeed> users = UserGenerator.generate(tenants, teams);

        Map<TeamKey, Long> leadCountByTeam = users.stream()
                .filter(u -> !u.unaffiliated())
                .collect(Collectors.groupingBy(
                        u -> new TeamKey(u.tenantKey(), u.teamIndex()),
                        Collectors.summingLong(u -> u.teamLead() ? 1 : 0)));

        assertThat(leadCountByTeam.values()).allMatch(count -> count == 1L);
        // Every affiliated team actually appears in the map (i.e. was checked) — guards against
        // a vacuously-true assertion if grouping silently produced no teams.
        assertThat(leadCountByTeam).hasSize(teams.size());
    }

    @Test
    void generate_always_unaffiliatedUsersHaveNoTeamAndAreNeverLeads() {
        List<TenantSeed> tenants = TenantGenerator.generate();
        List<TeamSeed> teams = TeamGenerator.generate(tenants, new Random(DatasetConfig.SEED));
        List<UserSeed> users = UserGenerator.generate(tenants, teams);

        for (UserSeed user : users) {
            if (user.unaffiliated()) {
                assertThat(user.teamIndex()).isNull();
                assertThat(user.teamLead()).isFalse();
            }
        }
    }

    @Test
    void generate_always_unaffiliatedCountPerTenantMatchesAffiliationSplit() {
        List<TenantSeed> tenants = TenantGenerator.generate();
        List<TeamSeed> teams = TeamGenerator.generate(tenants, new Random(DatasetConfig.SEED));
        List<UserSeed> users = UserGenerator.generate(tenants, teams);

        Map<TenantKey, Long> unaffiliatedByTenant = users.stream()
                .filter(UserSeed::unaffiliated)
                .collect(Collectors.groupingBy(UserSeed::tenantKey, Collectors.counting()));

        for (TenantSeed tenant : tenants) {
            long expected = AffiliationSplit.of(tenant.userCount()).unaffiliatedCount();
            long actual = unaffiliatedByTenant.getOrDefault(tenant.tenantKey(), 0L);
            assertThat(actual).isEqualTo(expected);
        }
    }

    @Test
    void generate_calledTwice_producesIdenticalOutput() {
        List<TenantSeed> tenants = TenantGenerator.generate();
        List<TeamSeed> teams = TeamGenerator.generate(tenants, new Random(DatasetConfig.SEED));

        assertThat(UserGenerator.generate(tenants, teams))
                .isEqualTo(UserGenerator.generate(tenants, teams));
    }
}
