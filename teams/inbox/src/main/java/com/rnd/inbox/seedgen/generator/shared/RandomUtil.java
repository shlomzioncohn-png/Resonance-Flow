package com.rnd.inbox.seedgen.generator.shared;
import com.rnd.inbox.seedgen.generator.tenant.TenantGenerator;

import com.rnd.inbox.seedgen.config.DatasetConfig.DoubleRange;
import com.rnd.inbox.seedgen.config.DatasetConfig.IntRange;

import java.util.Map;
import java.util.Random;

/**
 * Small helpers shared by every generation stage that needs a genuine random draw (as opposed to
 * {@link TenantGenerator}, which is pure arithmetic — see its class Javadoc).
 *
 * <p>Every stage takes its {@link Random} as a parameter rather than constructing its own, so a
 * caller controls the seed and the order stages consume from it — that fixed order is what makes
 * the whole pipeline reproducible (design doc §1): a fresh {@code new Random(DatasetConfig.SEED)},
 * run through the same stages in the same order, always draws the same sequence of values.
 *
 * <p>{@code public}: also used outside {@code .generator} by the materialization stage (e.g.
 * {@code DeadlineMaterializer}) — that stage is deliberately non-reproducible on its own terms
 * (a real "now"), but still draws its random offsets the same way every other stage does.
 */
public final class RandomUtil {

    private RandomUtil() {
    }

    /** A uniformly random value within an inclusive {@link DoubleRange}. */
    public static double inRange(Random random, DoubleRange range) {
        return range.min() + random.nextDouble() * (range.max() - range.min());
    }

    /** A uniformly random {@code double} within an inclusive {@link IntRange} — e.g. an average
     * team size, which is a real-valued input to a later rounding step, not a size itself. */
    public static double inRange(Random random, IntRange range) {
        return range.min() + random.nextDouble() * (range.max() - range.min());
    }

    /**
     * Draws one key from a percentage table (e.g. {@link com.rnd.inbox.seedgen.config.DatasetConfig#STATUS}),
     * weighted by its percentages — an independent per-call draw (design doc §6's approach): with
     * enough draws the actual share converges close to the target percentage, but not exactly,
     * the same way a fair die rolled 100,000 times won't land on each face precisely 16,667
     * times. Reproducible regardless — the same seed draws the same sequence of keys every run.
     *
     * <p>Iteration order over {@code percentages} doesn't affect which key gets chosen for a
     * given roll (any fixed order carves up the same 0-100 line the same way), only which
     * fraction of the roll axis each key occupies — the map's own (deterministic) order is fine.
     */
    public static String pick(Random random, Map<String, Double> percentages) {
        double roll = random.nextDouble() * 100;
        double cumulative = 0;
        String lastKey = null;
        for (Map.Entry<String, Double> entry : percentages.entrySet()) {
            cumulative += entry.getValue();
            lastKey = entry.getKey();
            if (roll < cumulative) {
                return entry.getKey();
            }
        }
        // Floating-point safety net: percentages sum to 100 exactly as validated ints/doubles,
        // but accumulated rounding could in principle leave `roll` just past the last boundary.
        return lastKey;
    }

    /**
     * An integer within {@code range}, skewed toward its midpoint rather than flat — design
     * doc §6's {@code priority_score}/{@code urgency} technique: average two independent
     * uniform draws instead of taking one. Extreme values need both draws to land near the
     * same extreme at once, which is less likely than either draw landing near the middle —
     * the same reason two dice sum to 7 far more often than to 2 or 12.
     */
    public static int intSkewedMid(Random random, IntRange range) {
        double first = inRange(random, range);
        double second = inRange(random, range);
        return (int) Math.round((first + second) / 2.0);
    }
}
