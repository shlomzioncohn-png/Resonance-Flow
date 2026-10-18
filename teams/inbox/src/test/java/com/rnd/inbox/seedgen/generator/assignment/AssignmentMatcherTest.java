package com.rnd.inbox.seedgen.generator.assignment;
import com.rnd.inbox.seedgen.generator.tenant.TenantGenerator;
import com.rnd.inbox.seedgen.generator.user.UserGenerator;
import com.rnd.inbox.seedgen.generator.task.TaskGenerator;
import com.rnd.inbox.seedgen.generator.team.TeamGenerator;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.task.TaskTenantSlicer;
import com.rnd.inbox.seedgen.generator.user.UserSeed;
import com.rnd.inbox.seedgen.generator.task.TaskSeed;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;
import com.rnd.inbox.seedgen.generator.team.TeamSeed;

import com.rnd.inbox.seedgen.config.DatasetConfig;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentMatcherTest {

    private record Fixture(List<TenantSeed> tenants, Map<TenantKey, List<TaskSeed>> tasksByTenant, AssignmentLoads loads) {
        static Fixture real(long seed) {
            List<TenantSeed> tenants = TenantGenerator.generate();
            List<TeamSeed> teams = TeamGenerator.generate(tenants, new Random(seed));
            List<UserSeed> users = UserGenerator.generate(tenants, teams);
            List<TaskSeed> tasks = TaskGenerator.generate(DatasetConfig.TOTAL_TASKS, new Random(seed));
            Map<TenantKey, List<TaskSeed>> tasksByTenant = TaskTenantSlicer.slice(tenants, tasks);
            AssignmentLoads loads = AssignmentGenerator.generate(tenants, teams, users, new Random(seed));
            return new Fixture(tenants, tasksByTenant, loads);
        }
    }

    @Test
    void generate_always_noTaskIsGivenToTheSameUserTwice() {
        Fixture fixture = Fixture.real(DatasetConfig.SEED);
        List<AssignmentRow> rows = AssignmentMatcher.generate(fixture.tenants(), fixture.tasksByTenant(), fixture.loads());

        Set<String> seen = new HashSet<>();
        for (AssignmentRow row : rows) {
            if (row.targetsTeam()) {
                continue;
            }
            String key = row.tenantKey() + ":" + row.taskIndexInTenant() + ":user:" + row.userIndexInTenant();
            assertThat(seen.add(key)).as("duplicate user assignment: %s", key).isTrue();
        }
    }

    @Test
    void generate_always_noTaskIsGivenToTheSameTeamTwice() {
        Fixture fixture = Fixture.real(DatasetConfig.SEED);
        List<AssignmentRow> rows = AssignmentMatcher.generate(fixture.tenants(), fixture.tasksByTenant(), fixture.loads());

        Set<String> seen = new HashSet<>();
        for (AssignmentRow row : rows) {
            if (!row.targetsTeam()) {
                continue;
            }
            String key = row.tenantKey() + ":" + row.taskIndexInTenant() + ":team:" + row.teamIndex();
            assertThat(seen.add(key)).as("duplicate team assignment: %s", key).isTrue();
        }
    }

    @Test
    void generate_always_everyRowsTaskIndexIsWithinItsTenantsTaskCount() {
        Fixture fixture = Fixture.real(DatasetConfig.SEED);
        List<AssignmentRow> rows = AssignmentMatcher.generate(fixture.tenants(), fixture.tasksByTenant(), fixture.loads());

        for (AssignmentRow row : rows) {
            int taskCount = fixture.tasksByTenant().get(row.tenantKey()).size();
            assertThat(row.taskIndexInTenant()).isBetween(0, taskCount - 1);
        }
    }

    @Test
    void generate_always_totalRowsPerTenantIsAtMostTheLoadTotals() {
        Fixture fixture = Fixture.real(DatasetConfig.SEED);
        List<AssignmentRow> rows = AssignmentMatcher.generate(fixture.tenants(), fixture.tasksByTenant(), fixture.loads());

        Map<TenantKey, Long> rowCountByTenant = rows.stream()
                .collect(Collectors.groupingBy(AssignmentRow::tenantKey, Collectors.counting()));
        Map<TenantKey, Integer> expectedByTenant = fixture.loads().userLoads().stream()
                .collect(Collectors.groupingBy(UserAssignmentLoad::tenantKey, Collectors.summingInt(UserAssignmentLoad::rowCount)));
        fixture.loads().teamLoads().forEach(t ->
                expectedByTenant.merge(t.tenantKey(), t.rowCount(), Integer::sum));

        // <= , not ==: a tenant so tiny that a task's second slot has no distinct entity left
        // (class Javadoc's degenerate case) drops that slot rather than duplicate — the one way
        // actual rows can fall short of the target.
        for (TenantSeed tenant : fixture.tenants()) {
            int expected = expectedByTenant.getOrDefault(tenant.tenantKey(), 0);
            long actual = rowCountByTenant.getOrDefault(tenant.tenantKey(), 0L);
            assertThat(actual).as("tenant %s", tenant.tenantKey()).isLessThanOrEqualTo(expected);
        }
    }

    @Test
    void generate_calledTwice_producesIdenticalOutput() {
        Fixture fixture = Fixture.real(DatasetConfig.SEED);

        List<AssignmentRow> first = AssignmentMatcher.generate(fixture.tenants(), fixture.tasksByTenant(), fixture.loads());
        List<AssignmentRow> second = AssignmentMatcher.generate(fixture.tenants(), fixture.tasksByTenant(), fixture.loads());

        assertThat(first).isEqualTo(second);
    }

    @Test
    void generate_severalSeeds_neverProducesADuplicateWithinAnyTenant() {
        for (long seed : new long[] {DatasetConfig.SEED, 0L, 1L, 42L, 999L}) {
            Fixture fixture = Fixture.real(seed);
            List<AssignmentRow> rows = AssignmentMatcher.generate(fixture.tenants(), fixture.tasksByTenant(), fixture.loads());

            Set<String> seen = new HashSet<>();
            for (AssignmentRow row : rows) {
                String type = row.targetsTeam() ? "team:" + row.teamIndex() : "user:" + row.userIndexInTenant();
                String key = row.tenantKey() + ":" + row.taskIndexInTenant() + ":" + type;
                assertThat(seen.add(key)).as("seed %s duplicate: %s", seed, key).isTrue();
            }
        }
    }
}
