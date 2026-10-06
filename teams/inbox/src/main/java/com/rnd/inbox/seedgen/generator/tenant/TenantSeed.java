package com.rnd.inbox.seedgen.generator.tenant;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;

/**
 * One tenant's computed size, before any row is actually built. {@code tierIndex} is this
 * tenant's position within its tier (0-based) — the same index a later stage reuses to derive
 * this tenant's other per-tenant parameters (sharing rate, concentration, ...) deterministically.
 *
 * @param tierName name of the size tier this tenant belongs to (design doc §2)
 * @param tierIndex 0-based position within the tier
 * @param taskCount exact number of tasks this tenant will own
 * @param userCount exact number of users this tenant will own
 */
public record TenantSeed(String tierName, int tierIndex, int taskCount, int userCount) {

    public TenantSeed {
        if (tierName == null || tierName.isBlank()) {
            throw new IllegalArgumentException("tierName must not be blank");
        }
        if (tierIndex < 0) {
            throw new IllegalArgumentException("tierIndex (" + tierIndex + ") must not be negative");
        }
        if (taskCount < 0) {
            throw new IllegalArgumentException("taskCount (" + taskCount + ") must not be negative");
        }
        if (userCount < 0) {
            throw new IllegalArgumentException("userCount (" + userCount + ") must not be negative");
        }
    }

    public TenantKey tenantKey() {
        return new TenantKey(tierName, tierIndex);
    }
}
