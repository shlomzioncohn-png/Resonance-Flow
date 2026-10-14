package com.rnd.inbox.seedgen.generator.assignment;
import com.rnd.inbox.seedgen.generator.shared.IntArrayMath;

/**
 * Splits {@code totalRows} exactly among {@code n} entities by a Pareto/power-law shape
 * (design doc §4's concentration parameter) — entity at position {@code i} (0-based) gets a
 * weight of {@code 1 / (i+1)^concentration}, so position 0 always outweighs every later
 * position; higher {@code concentration} makes that gap steeper. Pure arithmetic, not random —
 * same reasoning as {@link TenantGenerator}: given a tenant's already-drawn concentration value,
 * there's nothing left to roll.
 *
 * <p>Rounding each entity's share independently would rarely sum back to exactly
 * {@code totalRows}; the shortfall or surplus is corrected one unit at a time, always against
 * the entity currently holding the most (design doc §1's remainder-correction principle).
 * {@code indexOfMax} alone is safe even when shrinking: the loop only runs while
 * {@code assigned != totalRows}, and since {@code totalRows >= 0} is validated up front, a
 * shrink step (assigned > totalRows) can only happen when {@code assigned > 0} — meaning some
 * entity is always positive, so the max is never a share already at 0.
 */
final class PowerLawAllocator {

    private PowerLawAllocator() {
    }

    static int[] allocate(int totalRows, int n, double concentration) {
        if (totalRows < 0) {
            throw new IllegalArgumentException("totalRows (" + totalRows + ") must not be negative");
        }
        if (n <= 0) {
            throw new IllegalArgumentException("n (" + n + ") must be positive");
        }

        double[] weights = new double[n];
        double weightSum = 0;
        for (int i = 0; i < n; i++) {
            weights[i] = 1.0 / Math.pow(i + 1, concentration);
            weightSum += weights[i];
        }

        int[] shares = new int[n];
        int assigned = 0;
        for (int i = 0; i < n; i++) {
            shares[i] = (int) Math.round(totalRows * weights[i] / weightSum);
            assigned += shares[i];
        }

        int diff = totalRows - assigned;
        while (diff != 0) {
            int idx = IntArrayMath.indexOfMax(shares);
            shares[idx] += diff > 0 ? 1 : -1;
            diff += diff > 0 ? -1 : 1;
        }
        return shares;
    }
}
