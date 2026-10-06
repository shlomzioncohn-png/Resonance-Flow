package com.rnd.inbox.seedgen.generator.shared;

import com.rnd.inbox.seedgen.config.DatasetConfig.DoubleRange;
import com.rnd.inbox.seedgen.config.DatasetConfig.IntRange;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RandomUtilTest {

    @Test
    void inRange_doubleRange_alwaysWithinBounds() {
        Random random = new Random(1);
        DoubleRange range = new DoubleRange(0.5, 2.5);

        for (int i = 0; i < 1000; i++) {
            double value = RandomUtil.inRange(random, range);
            assertThat(value).isBetween(range.min(), range.max());
        }
    }

    @Test
    void inRange_intRange_alwaysWithinBounds() {
        Random random = new Random(2);
        IntRange range = new IntRange(4, 8);

        for (int i = 0; i < 1000; i++) {
            double value = RandomUtil.inRange(random, range);
            assertThat(value).isBetween((double) range.min(), (double) range.max());
        }
    }

    @Test
    void pick_singleKeyAtOneHundredPercent_alwaysReturnsThatKey() {
        Map<String, Double> onlyOption = Map.of("only", 100.0);
        Random random = new Random(3);

        for (int i = 0; i < 100; i++) {
            assertThat(RandomUtil.pick(random, onlyOption)).isEqualTo("only");
        }
    }

    @Test
    void pick_twoEvenKeys_bothAppearAcrossManyDraws() {
        Map<String, Double> evenSplit = new LinkedHashMap<>();
        evenSplit.put("a", 50.0);
        evenSplit.put("b", 50.0);
        Random random = new Random(4);

        long aCount = 0;
        long bCount = 0;
        for (int i = 0; i < 1000; i++) {
            String picked = RandomUtil.pick(random, evenSplit);
            if (picked.equals("a")) {
                aCount++;
            } else if (picked.equals("b")) {
                bCount++;
            }
        }

        // Loose bounds — this is a smoke test against "one key never gets picked", not a strict
        // statistical assertion. 1000 draws at 50/50 landing entirely outside [350, 650] would
        // itself indicate a bug (or spectacularly bad luck) in pick()'s cumulative logic.
        assertThat(aCount + bCount).isEqualTo(1000);
        assertThat(aCount).isBetween(350L, 650L);
        assertThat(bCount).isBetween(350L, 650L);
    }

    @Test
    void pick_sameSeed_producesIdenticalSequence() {
        Map<String, Double> percentages = new LinkedHashMap<>();
        percentages.put("a", 30.0);
        percentages.put("b", 70.0);

        Random first = new Random(42);
        Random second = new Random(42);

        for (int i = 0; i < 50; i++) {
            assertThat(RandomUtil.pick(first, percentages)).isEqualTo(RandomUtil.pick(second, percentages));
        }
    }

    @Test
    void intSkewedMid_always_withinRange() {
        Random random = new Random(5);
        IntRange range = new IntRange(1, 10);

        for (int i = 0; i < 1000; i++) {
            int value = RandomUtil.intSkewedMid(random, range);
            assertThat(value).isBetween(range.min(), range.max());
        }
    }

    @Test
    void intSkewedMid_acrossManyDraws_landsNearMiddleMoreOftenThanAtEitherExtreme() {
        Random random = new Random(6);
        IntRange range = new IntRange(1, 10);

        long middleCount = 0; // 5 or 6 — the two values straddling the exact midpoint 5.5
        long extremeCount = 0; // exactly 1 or exactly 10
        for (int i = 0; i < 10_000; i++) {
            int value = RandomUtil.intSkewedMid(random, range);
            if (value == 5 || value == 6) {
                middleCount++;
            } else if (value == range.min() || value == range.max()) {
                extremeCount++;
            }
        }

        assertThat(middleCount).isGreaterThan(extremeCount);
    }
}
