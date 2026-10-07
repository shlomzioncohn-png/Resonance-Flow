package com.rnd.inbox.seedgen.generator.team;
import com.rnd.inbox.seedgen.generator.shared.AffiliationSplit;
import com.rnd.inbox.seedgen.generator.shared.RandomUtil;
import com.rnd.inbox.seedgen.generator.shared.IntArrayMath;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;

import com.rnd.inbox.seedgen.config.DatasetConfig;
import com.rnd.inbox.seedgen.config.DatasetConfig.IntRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds each tenant's team structure (design doc §5): every user belongs to exactly one team,
 * team sizes are not uniform (range {@link DatasetConfig#TEAM_SIZE}), and the number of teams is
 * derived from a per-tenant average team size (range {@link DatasetConfig#AVG_TEAM_SIZE}).
 *
 * <p>Unlike {@link TenantGenerator}, this stage needs genuine randomness (the average team size
 * is drawn per tenant), so it takes a {@link Random} rather than computing everything from pure
 * arithmetic — see {@link RandomUtil}.
 */
public final class TeamGenerator {

    private TeamGenerator() {
    }

    /**
     * Generates every team for every tenant, in tenant list order. Team sizes are drawn from
     * each tenant's <em>affiliated</em> users only — the flat {@link DatasetConfig#UNAFFILIATED_USER_SHARE}
     * (design doc §5) never enters a team at all.
     */
    public static List<TeamSeed> generate(List<TenantSeed> tenants, Random random) {
        List<TeamSeed> teams = new ArrayList<>();
        for (TenantSeed tenant : tenants) {
            int affiliatedUsers = AffiliationSplit.of(tenant.userCount()).affiliatedCount();
            List<Integer> sizes = teamSizesForTenant(affiliatedUsers, random);
            for (int i = 0; i < sizes.size(); i++) {
                teams.add(new TeamSeed(tenant.tierName(), tenant.tierIndex(), i, sizes.get(i)));
            }
        }
        return List.copyOf(teams);
    }

    /**
     * Team sizes for one tenant, summing to exactly {@code tenantUsers}. Every user belongs to
     * exactly one team (design doc §5), so this is really "partition {@code tenantUsers} into
     * {@code teamCount} non-uniform, in-range groups" — the interesting part is picking a
     * {@code teamCount} that is actually achievable with {@link DatasetConfig#TEAM_SIZE}'s
     * bounds, since a tenant can be too small (or, in principle, too large) for the average team
     * size a naive draw would otherwise suggest.
     */
    // Package-private (not private) so TeamGeneratorTest can exercise it directly, including the
    // negative-input guard below — unreachable through generate() itself now that TenantSeed's
    // own constructor already rejects a negative userCount before it gets here.
    static List<Integer> teamSizesForTenant(int tenantUsers, Random random) {
        if (tenantUsers < 0) {
            throw new IllegalArgumentException("tenantUsers (" + tenantUsers + ") must not be negative");
        }

        IntRange teamSize = DatasetConfig.TEAM_SIZE;

        // No affiliated users left (only possible for a tenant so small the unaffiliated share
        // rounds up to all of it) — no teams, not a zero-member team.
        if (tenantUsers == 0) {
            return List.of();
        }

        // A tenant smaller than one minimum-sized team is a single small team — the "team of
        // one" edge case design doc §5 calls out, not an error.
        if (tenantUsers <= teamSize.min()) {
            return List.of(tenantUsers);
        }

        double avgTeamSize = RandomUtil.inRange(random, DatasetConfig.AVG_TEAM_SIZE);
        int desiredTeamCount = Math.max(1, (int) Math.round(tenantUsers / avgTeamSize));

        // Clamp to what's actually achievable: at least enough teams that TEAM_SIZE.max() can
        // cover everyone, at most as many as TEAM_SIZE.min() allows without going below it.
        int minAchievable = (int) Math.ceil((double) tenantUsers / teamSize.max());
        int maxAchievable = Math.max(1, tenantUsers / teamSize.min());
        int teamCount = Math.min(Math.max(desiredTeamCount, Math.max(1, minAchievable)), maxAchievable);

        if (teamCount == 1) {
            return List.of(tenantUsers);
        }

        // Baseline: linear spread across [min, max], same formula as TenantGenerator.
        int[] sizes = new int[teamCount];
        for (int i = 0; i < teamCount; i++) {
            double ratio = (double) i / (teamCount - 1);
            sizes[i] = (int) Math.round(teamSize.min() + (teamSize.max() - teamSize.min()) * ratio);
        }

        // Remainder correction, one member at a time, only moving a team that stays in range —
        // unlike Mega (no upper bound), every team here has a real ceiling, so a single-shot
        // correction could overshoot it. teamCount's clamping above guarantees this terminates.
        int diff = tenantUsers - IntArrayMath.sumOf(sizes);
        while (diff != 0) {
            int idx = diff > 0 ? IntArrayMath.indexOfMin(sizes) : IntArrayMath.indexOfMax(sizes);
            sizes[idx] += diff > 0 ? 1 : -1;
            diff += diff > 0 ? -1 : 1;
        }

        List<Integer> result = new ArrayList<>(teamCount);
        for (int size : sizes) {
            result.add(size);
        }
        return result;
    }
}
