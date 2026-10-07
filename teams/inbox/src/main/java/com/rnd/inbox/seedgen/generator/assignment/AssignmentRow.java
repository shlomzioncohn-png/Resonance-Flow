package com.rnd.inbox.seedgen.generator.assignment;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.task.TaskTenantSlicer;
import com.rnd.inbox.seedgen.generator.user.UserSeed;
import com.rnd.inbox.seedgen.generator.team.TeamSeed;

/**
 * One final {@code assignments} row: a specific task, in a specific tenant, given to a specific
 * user <em>or</em> a specific team — never both, mirroring the schema's
 * {@code chk_assignments_target} constraint. This is the end product of the matching stage
 * (design doc §4) — unlike {@link UserAssignmentLoad}/{@link TeamAssignmentLoad}, which only
 * carry a target *count*, this names the actual task.
 *
 * @param taskIndexInTenant 0-based position of the task within its tenant's slice
 *                          ({@link TaskTenantSlicer}) — tasks don't have a real id yet at this stage
 * @param userIndexInTenant matches {@link UserSeed#userIndexInTenant()}, or {@code null} if this
 *                           row targets a team instead
 * @param teamIndex matches {@link TeamSeed#teamIndex()}, or {@code null} if this row targets a
 *                  user instead — exactly one of {@code userIndexInTenant}/{@code teamIndex} is
 *                  non-null
 */
public record AssignmentRow(
        String tenantTierName,
        int tenantTierIndex,
        int taskIndexInTenant,
        Integer userIndexInTenant,
        Integer teamIndex) {

    public AssignmentRow {
        if (tenantTierName == null || tenantTierName.isBlank()) {
            throw new IllegalArgumentException("tenantTierName must not be blank");
        }
        if (tenantTierIndex < 0) {
            throw new IllegalArgumentException("tenantTierIndex (" + tenantTierIndex + ") must not be negative");
        }
        if (taskIndexInTenant < 0) {
            throw new IllegalArgumentException("taskIndexInTenant (" + taskIndexInTenant + ") must not be negative");
        }
        if (userIndexInTenant != null && userIndexInTenant < 0) {
            throw new IllegalArgumentException("userIndexInTenant (" + userIndexInTenant + ") must not be negative");
        }
        if (teamIndex != null && teamIndex < 0) {
            throw new IllegalArgumentException("teamIndex (" + teamIndex + ") must not be negative");
        }
        boolean hasUser = userIndexInTenant != null;
        boolean hasTeam = teamIndex != null;
        if (hasUser == hasTeam) {
            throw new IllegalArgumentException(
                    "exactly one of userIndexInTenant/teamIndex must be set (schema's chk_assignments_target)");
        }
    }

    public boolean targetsTeam() {
        return teamIndex != null;
    }

    public TenantKey tenantKey() {
        return new TenantKey(tenantTierName, tenantTierIndex);
    }
}
