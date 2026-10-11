package com.rnd.inbox.seedgen.generator.team;
import com.rnd.inbox.seedgen.generator.shared.AffiliationSplit;
import com.rnd.inbox.seedgen.generator.tenant.TenantGenerator;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;

import com.rnd.inbox.seedgen.config.DatasetConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TeamGeneratorTest {

    /** Real seed plus a spread of others, so the range-safety checks aren't tied to one draw. */
    private static final long[] SAMPLE_SEEDS = {DatasetConfig.SEED, 0L, 1L, 42L, 999L, 123456L};

    @Test
    void generate_always_everyTenantsTeamSizesSumToItsAffiliatedUserCount() {
        List<TenantSeed> tenants = TenantGenerator.generate();

        for (long seed : SAMPLE_SEEDS) {
            List<TeamSeed> teams = TeamGenerator.generate(tenants, new Random(seed));
            Map<TenantKey, Integer> sizeSumByTenant = teams.stream()
                    .collect(Collectors.groupingBy(TeamSeed::tenantKey, Collectors.summingInt(TeamSeed::size)));

            for (TenantSeed tenant : tenants) {
                int affiliated = AffiliationSplit.of(tenant.userCount()).affiliatedCount();
                // sizeSumByTenant has no entry at all when a tenant ends up with zero teams
                // (only possible if affiliated rounds to 0) — treat that as sum 0.
                assertThat(sizeSumByTenant.getOrDefault(tenant.tenantKey(), 0)).isEqualTo(affiliated);
            }
        }
    }

    @Test
    void generate_always_everyTeamIsWithinRangeOrTheSoleTeamForItsTenant() {
        List<TenantSeed> tenants = TenantGenerator.generate();
        IntRangeBounds bounds = new IntRangeBounds(
                DatasetConfig.TEAM_SIZE.min(), DatasetConfig.TEAM_SIZE.max());

        for (long seed : SAMPLE_SEEDS) {
            List<TeamSeed> teams = TeamGenerator.generate(tenants, new Random(seed));
            Map<TenantKey, Long> teamCountByTenant = teams.stream()
                    .collect(Collectors.groupingBy(TeamSeed::tenantKey, Collectors.counting()));

            for (TeamSeed team : teams) {
                boolean isSoleTeam = teamCountByTenant.get(team.tenantKey()) == 1L;
                boolean inRange = team.size() >= bounds.min() && team.size() <= bounds.max();

                assertThat(inRange || isSoleTeam)
                        .as("team %s should be in range %s or the sole team for its tenant",
                                team, bounds)
                        .isTrue();
                assertThat(team.size()).isPositive();
            }
        }
    }

    @Test
    void generate_sameSeed_producesIdenticalOutput() {
        List<TenantSeed> tenants = TenantGenerator.generate();

        List<TeamSeed> first = TeamGenerator.generate(tenants, new Random(DatasetConfig.SEED));
        List<TeamSeed> second = TeamGenerator.generate(tenants, new Random(DatasetConfig.SEED));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void generate_tenantSmallerThanMinTeamSize_returnsSingleTeamOfThatSize() {
        TenantSeed tinyTenant = new TenantSeed("Micro/Trial", 0, 5, 1);

        List<TeamSeed> teams = TeamGenerator.generate(List.of(tinyTenant), new Random(DatasetConfig.SEED));

        assertThat(teams).hasSize(1);
        assertThat(teams.get(0).size()).isEqualTo(1);
    }

    @Test
    void teamSizesForTenant_negativeTenantUsers_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TeamGenerator.teamSizesForTenant(-1, new Random(DatasetConfig.SEED)));
    }

    private record IntRangeBounds(int min, int max) {
    }
}
