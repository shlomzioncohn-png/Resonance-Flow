package com.rnd.inbox.seedgen.generator.user;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;
import com.rnd.inbox.seedgen.generator.team.TeamSeed;

/**
 * One user's placement, before any row is actually built. Identifies the owning tenant the same
 * way {@link TenantSeed} and {@link TeamSeed} do (no real ids yet at this stage).
 *
 * @param tenantTierName tier of the tenant this user belongs to
 * @param tenantTierIndex 0-based position of that tenant within its tier
 * @param teamIndex 0-based position of this user's team within its tenant (matches
 *                  {@link TeamSeed#teamIndex()}), or {@code null} for the unaffiliated-user edge
 *                  case (design doc §5) — this user gets no {@code team_members} row at all
 * @param userIndexInTenant 0-based position of this user within its tenant (across all its teams
 *                          and any unaffiliated users)
 * @param teamLead whether this user is their team's {@code team_lead_id} — always {@code false}
 *                 when {@code teamIndex} is {@code null}
 */
public record UserSeed(
        String tenantTierName,
        int tenantTierIndex,
        Integer teamIndex,
        int userIndexInTenant,
        boolean teamLead) {

    public UserSeed {
        if (tenantTierName == null || tenantTierName.isBlank()) {
            throw new IllegalArgumentException("tenantTierName must not be blank");
        }
        if (tenantTierIndex < 0) {
            throw new IllegalArgumentException("tenantTierIndex (" + tenantTierIndex + ") must not be negative");
        }
        if (teamIndex != null && teamIndex < 0) {
            throw new IllegalArgumentException("teamIndex (" + teamIndex + ") must not be negative");
        }
        if (userIndexInTenant < 0) {
            throw new IllegalArgumentException("userIndexInTenant (" + userIndexInTenant + ") must not be negative");
        }
        if (teamIndex == null && teamLead) {
            throw new IllegalArgumentException("An unaffiliated user (teamIndex == null) cannot be a team lead");
        }
    }

    /** {@code true} for the unaffiliated-user edge case — no team at all. */
    public boolean unaffiliated() {
        return teamIndex == null;
    }

    public TenantKey tenantKey() {
        return new TenantKey(tenantTierName, tenantTierIndex);
    }
}
