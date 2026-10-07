package com.rnd.inbox.seedgen.generator.task;
import com.rnd.inbox.seedgen.generator.tenant.TenantGenerator;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;

import com.rnd.inbox.seedgen.config.DatasetConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TaskTenantSlicerTest {

    @Test
    void slice_always_everyTenantsSliceSizeMatchesItsTaskCount() {
        List<TenantSeed> tenants = TenantGenerator.generate();
        List<TaskSeed> tasks = TaskGenerator.generate(DatasetConfig.TOTAL_TASKS, new Random(DatasetConfig.SEED));

        Map<TenantKey, List<TaskSeed>> sliceByTenant = TaskTenantSlicer.slice(tenants, tasks);

        for (TenantSeed tenant : tenants) {
            assertThat(sliceByTenant.get(tenant.tenantKey())).hasSize(tenant.taskCount());
        }
    }

    @Test
    void slice_always_everyTaskIsClaimedExactlyOnce() {
        List<TenantSeed> tenants = TenantGenerator.generate();
        List<TaskSeed> tasks = TaskGenerator.generate(DatasetConfig.TOTAL_TASKS, new Random(DatasetConfig.SEED));

        Map<TenantKey, List<TaskSeed>> sliceByTenant = TaskTenantSlicer.slice(tenants, tasks);

        int totalSliced = sliceByTenant.values().stream().mapToInt(List::size).sum();
        assertThat(totalSliced).isEqualTo(DatasetConfig.TOTAL_TASKS);
    }

    @Test
    void slice_mismatchedTotal_throws() {
        List<TenantSeed> tenants = TenantGenerator.generate();
        List<TaskSeed> tooFew = TaskGenerator.generate(DatasetConfig.TOTAL_TASKS - 1, new Random(DatasetConfig.SEED));

        assertThatIllegalArgumentException().isThrownBy(() -> TaskTenantSlicer.slice(tenants, tooFew));
    }

    @Test
    void slice_everyTenantHasAnEntryEvenTheSmallest() {
        List<TenantSeed> tenants = TenantGenerator.generate();
        List<TaskSeed> tasks = TaskGenerator.generate(DatasetConfig.TOTAL_TASKS, new Random(DatasetConfig.SEED));

        Map<TenantKey, List<TaskSeed>> sliceByTenant = TaskTenantSlicer.slice(tenants, tasks);

        assertThat(sliceByTenant).hasSize(tenants.size());
    }
}
