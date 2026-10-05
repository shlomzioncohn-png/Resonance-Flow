package com.rnd.inbox.seedgen.generator.tenant;

import com.rnd.inbox.seedgen.config.DatasetConfig;
import com.rnd.inbox.seedgen.config.DatasetConfig.IntRange;
import com.rnd.inbox.seedgen.config.DatasetConfig.TenantTier;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the 100 {@link TenantSeed}s described in design doc §1–§2: six size tiers, task counts
 * spread linearly within each tier, and the single Mega tenant absorbing whatever remains so the
 * grand total across all tenants is exactly {@link DatasetConfig#TOTAL_TASKS}.
 *
 * <p>Deliberately not random: every count here comes from the arithmetic spread formula alone
 * (design doc §1, "a simple, auditable rule, not an opaque random draw"). {@link DatasetConfig#SEED}
 * is not used at this stage — it starts mattering once later stages (teams, field values,
 * assignments) need genuine random draws.
 */
public final class TenantGenerator {

    private TenantGenerator() {
    }

    /** Generates all 100 tenants, Mega first, then the other five tiers in design-doc order. */
    public static List<TenantSeed> generate() {
        List<TenantSeed> nonMega = new ArrayList<>();
        int nonMegaTaskSum = 0;

        for (TenantTier tier : DatasetConfig.TENANT_TIERS) {
            if (tier == DatasetConfig.MEGA) {
                continue; // Mega's task count depends on every other tenant — computed last.
            }
            for (int i = 0; i < tier.tenantCount(); i++) {
                int tasks = spread(tier.tasks(), i, tier.tenantCount());
                int users = spread(tier.users(), i, tier.tenantCount());
                nonMega.add(new TenantSeed(tier.name(), i, tasks, users));
                nonMegaTaskSum += tasks;
            }
        }

        int megaTasks = DatasetConfig.TOTAL_TASKS - nonMegaTaskSum;
        int megaUsers = spread(DatasetConfig.MEGA.users(), 0, 1);

        List<TenantSeed> all = new ArrayList<>();
        all.add(new TenantSeed(DatasetConfig.MEGA.name(), 0, megaTasks, megaUsers));
        all.addAll(nonMega);
        return List.copyOf(all);
    }

    /**
     * Linear spread of {@code range} across {@code n} positions (design doc §1):
     * {@code round(min + (max - min) * i / (n - 1))}. A tier of size 1 (only Mega's user count
     * uses this) has no spread to do, so it takes the range's midpoint instead.
     */
    private static int spread(IntRange range, int i, int n) {
        if (n == 1) {
            return (int) Math.round((range.min() + range.max()) / 2.0);
        }
        double ratio = (double) i / (n - 1);
        return (int) Math.round(range.min() + (range.max() - range.min()) * ratio);
    }
}
