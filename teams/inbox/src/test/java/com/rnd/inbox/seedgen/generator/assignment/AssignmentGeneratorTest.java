package com.rnd.inbox.seedgen.generator.assignment;
import com.rnd.inbox.seedgen.generator.tenant.TenantGenerator;
import com.rnd.inbox.seedgen.generator.user.UserGenerator;
import com.rnd.inbox.seedgen.generator.team.TeamGenerator;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.user.UserSeed;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;
import com.rnd.inbox.seedgen.generator.team.TeamSeed;

import com.rnd.inbox.seedgen.config.DatasetConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentGeneratorTest {

    private static final long[] SAMPLE_SEEDS = {DatasetConfig.SEED, 0L, 1L, 42L, 999L};

    private record Fixture(List<TenantSeed> tenants, List<TeamSeed> teams, List<UserSeed> users) {
        static Fixture real(long seed) {
            List<TenantSeed> tenants = TenantGenerator.generate();
            List<TeamSeed> teams = TeamGenerator.generate(tenants, new Random(seed));
            List<UserSeed> users = UserGenerator.generate(tenants, teams);
            return new Fixture(tenants, teams, users);
        }
    }

    @Test
    void generate_always_everyTenantsRowsFallWithinTheSharingRateBounds() {
        for (long seed : SAMPLE_SEEDS) {
            Fixture fixture = Fixture.real(seed);
            AssignmentLoads loads = AssignmentGenerator.generate(
                    fixture.tenants(), fixture.teams(), fixture.users(), new Random(seed));

            Map<TenantKey, Integer> userRowsByTenant = sumByTenant(loads.userLoads(),
                    UserAssignmentLoad::tenantKey, UserAssignmentLoad::rowCount);
            Map<TenantKey, Integer> teamRowsByTenant = sumByTenant(loads.teamLoads(),
                    TeamAssignmentLoad::tenantKey, TeamAssignmentLoad::rowCount);

            for (TenantSeed tenant : fixture.tenants()) {
                int totalRows = userRowsByTenant.getOrDefault(tenant.tenantKey(), 0)
                        + teamRowsByTenant.getOrDefault(tenant.tenantKey(), 0);

                int minExpected = tenant.taskCount()
                        + (int) Math.round(tenant.taskCount() * DatasetConfig.SHARING_RATE.min());
                int maxExpected = tenant.taskCount()
                        + (int) Math.round(tenant.taskCount() * DatasetConfig.SHARING_RATE.max());

                // Rounding at both the shared-task-count and per-tier steps can land a task's
                // total a unit or two outside the naive bound — widen by 2 to absorb that.
                assertThat(totalRows).as("tenant %s", tenant.tenantKey())
                        .isBetween(minExpected - 2, maxExpected + 2);
            }
        }
    }

    @Test
    void generate_always_noNegativeLoads() {
        for (long seed : SAMPLE_SEEDS) {
            Fixture fixture = Fixture.real(seed);
            AssignmentLoads loads = AssignmentGenerator.generate(
                    fixture.tenants(), fixture.teams(), fixture.users(), new Random(seed));

            assertThat(loads.userLoads()).allSatisfy(l -> assertThat(l.rowCount()).isGreaterThanOrEqualTo(0));
            assertThat(loads.teamLoads()).allSatisfy(l -> assertThat(l.rowCount()).isGreaterThanOrEqualTo(0));
        }
    }

    @Test
    void generate_always_everyUserAndTeamHasExactlyOneLoadEntry() {
        Fixture fixture = Fixture.real(DatasetConfig.SEED);
        AssignmentLoads loads = AssignmentGenerator.generate(
                fixture.tenants(), fixture.teams(), fixture.users(), new Random(DatasetConfig.SEED));

        // Every user is a candidate for a user-targeted row, affiliated or not (design doc §4).
        assertThat(loads.userLoads()).hasSize(fixture.users().size());
        assertThat(loads.teamLoads()).hasSize(fixture.teams().size());
    }

    @Test
    void generate_sameSeed_producesIdenticalOutput() {
        Fixture fixture = Fixture.real(DatasetConfig.SEED);

        AssignmentLoads first = AssignmentGenerator.generate(
                fixture.tenants(), fixture.teams(), fixture.users(), new Random(DatasetConfig.SEED));
        AssignmentLoads second = AssignmentGenerator.generate(
                fixture.tenants(), fixture.teams(), fixture.users(), new Random(DatasetConfig.SEED));

        assertThat(first).isEqualTo(second);
    }

    private static <T> Map<TenantKey, Integer> sumByTenant(
            List<T> items, java.util.function.Function<T, TenantKey> keyFn, java.util.function.ToIntFunction<T> valueFn) {
        return items.stream().collect(Collectors.groupingBy(keyFn, Collectors.summingInt(valueFn)));
    }
}
