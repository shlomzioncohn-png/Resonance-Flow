package com.rnd.inbox.seedgen.generator.assignment;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.user.UserSeed;

/**
 * How many {@code assignments} rows (design doc §4) one affiliated user ends up with — a task
 * they're an {@code owner}/{@code assignee}/{@code responsible}/{@code collaborator} on.
 *
 * @param userIndexInTenant matches {@link UserSeed#userIndexInTenant()}
 * @param rowCount number of assignment rows targeting this user
 */
public record UserAssignmentLoad(String tenantTierName, int tenantTierIndex, int userIndexInTenant, int rowCount) {

    public UserAssignmentLoad {
        if (tenantTierName == null || tenantTierName.isBlank()) {
            throw new IllegalArgumentException("tenantTierName must not be blank");
        }
        if (tenantTierIndex < 0) {
            throw new IllegalArgumentException("tenantTierIndex (" + tenantTierIndex + ") must not be negative");
        }
        if (userIndexInTenant < 0) {
            throw new IllegalArgumentException("userIndexInTenant (" + userIndexInTenant + ") must not be negative");
        }
        if (rowCount < 0) {
            throw new IllegalArgumentException("rowCount (" + rowCount + ") must not be negative");
        }
    }

    public TenantKey tenantKey() {
        return new TenantKey(tenantTierName, tenantTierIndex);
    }
}
