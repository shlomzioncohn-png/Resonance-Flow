package com.rnd.inbox.seedgen;

import com.rnd.inbox.seedgen.generator.assignment.AssignmentRow;
import com.rnd.inbox.seedgen.generator.task.TaskSeed;
import com.rnd.inbox.seedgen.generator.team.TeamSeed;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;
import com.rnd.inbox.seedgen.generator.user.UserSeed;

import java.util.List;
import java.util.Map;

/**
 * Every generation stage's output, bundled together — the full ~100,000-task dataset (design
 * doc), still one step before real CSV rows: ids are still positional
 * ({@code tierName}/{@code tierIndex}/{@code userIndexInTenant}/... — see each Seed type), not
 * real UUIDs, and {@link TaskSeed}'s deadline is still a category, not a timestamp. Materializing
 * those is the next stage, not this one.
 */
public record GeneratedDataset(
        List<TenantSeed> tenants,
        List<TeamSeed> teams,
        List<UserSeed> users,
        Map<TenantKey, List<TaskSeed>> tasksByTenant,
        List<AssignmentRow> assignments) {
}
