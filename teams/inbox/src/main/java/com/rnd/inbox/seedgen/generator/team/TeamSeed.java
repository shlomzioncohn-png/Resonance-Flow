package com.rnd.inbox.seedgen.generator.team;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;

/**
 * One team's computed size, before any row is actually built. Identifies its owning tenant by
 * the same {@code (tierName, tenantTierIndex)} pair {@link TenantSeed} uses, since tenants don't
 * have a real id yet at this stage.
 *
 * @param tenantTierName tier of the tenant this team belongs to
 * @param tenantTierIndex 0-based position of that tenant within its tier
 * @param teamIndex 0-based position of this team within its tenant
 * @param size number of members this team will have
 */
public record TeamSeed(String tenantTierName, int tenantTierIndex, int teamIndex, int size) {

    public TeamSeed {
        if (tenantTierName == null || tenantTierName.isBlank()) {
            throw new IllegalArgumentException("tenantTierName must not be blank");
        }
        if (tenantTierIndex < 0) {
            throw new IllegalArgumentException("tenantTierIndex (" + tenantTierIndex + ") must not be negative");
        }
        if (teamIndex < 0) {
            throw new IllegalArgumentException("teamIndex (" + teamIndex + ") must not be negative");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size (" + size + ") must be positive — a team can't have zero or fewer members");
        }
    }

    public TenantKey tenantKey() {
        return new TenantKey(tenantTierName, tenantTierIndex);
    }
}
