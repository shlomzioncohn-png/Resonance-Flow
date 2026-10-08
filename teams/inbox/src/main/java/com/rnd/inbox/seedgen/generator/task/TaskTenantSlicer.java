package com.rnd.inbox.seedgen.generator.task;
import com.rnd.inbox.seedgen.generator.tenant.TenantGenerator;
import com.rnd.inbox.seedgen.generator.assignment.AssignmentGenerator;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Links the {@link TaskGenerator}'s global, tenant-unaware task list back to specific tenants —
 * the missing piece {@link AssignmentGenerator} needs before any task can actually be matched to
 * a specific user or team (design doc §4). Pure arithmetic, not random: tenant {@code i} simply
 * claims the next {@code tenant.taskCount()} tasks off the front of the list, in tenant order —
 * valid because {@link TenantGenerator} already guarantees the tenants' task counts sum to
 * exactly {@link com.rnd.inbox.seedgen.config.DatasetConfig#TOTAL_TASKS}, the same size
 * {@link TaskGenerator#generate} was asked to produce.
 */
public final class TaskTenantSlicer {

    private TaskTenantSlicer() {
    }

    /**
     * @param tenants the 100 tenants, in the order their task counts should be claimed
     * @param tasks the full global task list — must have exactly as many tasks as the tenants'
     *              task counts sum to
     * @return each tenant's own slice, same size as {@code tenant.taskCount()}, keyed by
     *         {@link TenantSeed#tenantKey()}
     */
    public static Map<TenantKey, List<TaskSeed>> slice(List<TenantSeed> tenants, List<TaskSeed> tasks) {
        int totalExpected = tenants.stream().mapToInt(TenantSeed::taskCount).sum();
        if (totalExpected != tasks.size()) {
            throw new IllegalArgumentException(
                    "tenants' task counts sum to " + totalExpected + " but tasks has " + tasks.size() + " entries");
        }

        Map<TenantKey, List<TaskSeed>> sliceByTenant = new LinkedHashMap<>();
        int cursor = 0;
        for (TenantSeed tenant : tenants) {
            int count = tenant.taskCount();
            sliceByTenant.put(tenant.tenantKey(), List.copyOf(tasks.subList(cursor, cursor + count)));
            cursor += count;
        }
        return sliceByTenant;
    }
}
