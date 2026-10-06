package com.rnd.inbox.seedgen.generator.assignment;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.team.TeamSeed;

/**
 * How many {@code assignments} rows (design doc §4) one team ends up with — a task assigned to
 * the team as a whole ({@code assignments.team_id}) rather than to a specific person.
 *
 * @param teamIndex matches {@link TeamSeed#teamIndex()}
 * @param rowCount number of assignment rows targeting this team
 */
public record TeamAssignmentLoad(String tenantTierName, int tenantTierIndex, int teamIndex, int rowCount) {

    public TeamAssignmentLoad {
        if (tenantTierName == null || tenantTierName.isBlank()) {
            throw new IllegalArgumentException("tenantTierName must not be blank");
        }
        if (tenantTierIndex < 0) {
            throw new IllegalArgumentException("tenantTierIndex (" + tenantTierIndex + ") must not be negative");
        }
        if (teamIndex < 0) {
            throw new IllegalArgumentException("teamIndex (" + teamIndex + ") must not be negative");
        }
        if (rowCount < 0) {
            throw new IllegalArgumentException("rowCount (" + rowCount + ") must not be negative");
        }
    }

    public TenantKey tenantKey() {
        return new TenantKey(tenantTierName, tenantTierIndex);
    }
}
