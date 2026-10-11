package com.rnd.inbox.seedgen.generator.shared;
import com.rnd.inbox.seedgen.generator.tenant.TenantGenerator;
import com.rnd.inbox.seedgen.generator.team.TeamGenerator;

import com.rnd.inbox.seedgen.config.DatasetConfig;

/**
 * Splits a tenant's users into "affiliated" (belongs to a team) and "unaffiliated" (no
 * {@code team_members} row at all — design doc §5 edge case), by
 * {@link DatasetConfig#UNAFFILIATED_USER_SHARE}.
 *
 * <p>Pure arithmetic, not random — same reasoning as {@link TenantGenerator}: a flat global
 * share, so there's nothing to draw per tenant. {@link TeamGenerator} and the future
 * {@code UserGenerator} both need the exact same split for the same tenant, hence one shared
 * place to compute it instead of two.
 *
 * @param tenantUsers total users in the tenant
 * @param unaffiliatedCount how many of them have no team
 * @param affiliatedCount how many of them belong to exactly one team ({@code tenantUsers - unaffiliatedCount})
 */
public record AffiliationSplit(int tenantUsers, int unaffiliatedCount, int affiliatedCount) {

    public AffiliationSplit {
        if (tenantUsers < 0 || unaffiliatedCount < 0 || affiliatedCount < 0) {
            throw new IllegalArgumentException(
                    "Counts must not be negative: tenantUsers=" + tenantUsers
                            + ", unaffiliated=" + unaffiliatedCount + ", affiliated=" + affiliatedCount);
        }
        if (unaffiliatedCount + affiliatedCount != tenantUsers) {
            throw new IllegalArgumentException(
                    "unaffiliated (" + unaffiliatedCount + ") + affiliated (" + affiliatedCount
                            + ") must equal tenantUsers (" + tenantUsers + ")");
        }
    }

    public static AffiliationSplit of(int tenantUsers) {
        int unaffiliated = (int) Math.round(tenantUsers * DatasetConfig.UNAFFILIATED_USER_SHARE);
        return new AffiliationSplit(tenantUsers, unaffiliated, tenantUsers - unaffiliated);
    }
}
