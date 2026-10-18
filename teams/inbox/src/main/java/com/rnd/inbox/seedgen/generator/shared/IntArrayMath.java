package com.rnd.inbox.seedgen.generator.shared;

import com.rnd.inbox.seedgen.generator.team.TeamGenerator;

/**
 * Small array helpers shared by every remainder-correction loop in the generator (design doc
 * §1's "largest/smallest absorbs the remainder" principle, applied one unit at a time wherever a
 * single-shot correction could overshoot a bound) — {@link TeamGenerator} and
 * {@code PowerLawAllocator} both need the same three operations, so they live here once instead
 * of as private copies in each.
 *
 * <p>Public (not package-private) because, after the package-by-feature split, its callers live
 * in other {@code seedgen.generator} sub-packages ({@code team}, {@code assignment}) rather than
 * alongside it here — the same reason {@link RandomUtil} is public.
 */
public final class IntArrayMath {

    private IntArrayMath() {
    }

    public static int sumOf(int[] values) {
        int sum = 0;
        for (int v : values) {
            sum += v;
        }
        return sum;
    }

    public static int indexOfMin(int[] values) {
        int idx = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] < values[idx]) {
                idx = i;
            }
        }
        return idx;
    }

    public static int indexOfMax(int[] values) {
        int idx = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[idx]) {
                idx = i;
            }
        }
        return idx;
    }
}
