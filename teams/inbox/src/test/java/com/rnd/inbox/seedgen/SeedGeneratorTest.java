package com.rnd.inbox.seedgen;

import com.rnd.inbox.seedgen.config.DatasetConfig;
import com.rnd.inbox.seedgen.generator.task.TaskSeed;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeedGeneratorTest {

    @Test
    void generate_always_producesExactlyOneHundredTenants() {
        assertThat(SeedGenerator.generate().tenants()).hasSize(100);
    }

    @Test
    void generate_always_totalTasksAcrossAllTenantsIsExactlyOneHundredThousand() {
        GeneratedDataset dataset = SeedGenerator.generate();

        int totalTasks = dataset.tasksByTenant().values().stream().mapToInt(List::size).sum();

        assertThat(totalTasks).isEqualTo(DatasetConfig.TOTAL_TASKS);
    }

    @Test
    void generate_always_totalUsersMatchesTheTenantsUserCounts() {
        GeneratedDataset dataset = SeedGenerator.generate();

        int expectedUsers = dataset.tenants().stream().mapToInt(TenantSeed::userCount).sum();

        assertThat(dataset.users()).hasSize(expectedUsers);
    }

    @Test
    void generate_always_everyTenantHasATaskSlice() {
        GeneratedDataset dataset = SeedGenerator.generate();

        for (TenantSeed tenant : dataset.tenants()) {
            List<TaskSeed> slice = dataset.tasksByTenant().get(tenant.tenantKey());
            assertThat(slice).as("tenant %s", tenant.tenantKey()).hasSize(tenant.taskCount());
        }
    }

    @Test
    void generate_always_producesSomeAssignmentRows() {
        assertThat(SeedGenerator.generate().assignments()).isNotEmpty();
    }

    @Test
    void generate_calledTwice_producesIdenticalOutput() {
        GeneratedDataset first = SeedGenerator.generate();
        GeneratedDataset second = SeedGenerator.generate();

        assertThat(first).isEqualTo(second);
    }
}
