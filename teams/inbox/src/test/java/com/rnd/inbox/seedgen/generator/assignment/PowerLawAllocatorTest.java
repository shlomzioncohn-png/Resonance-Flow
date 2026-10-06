package com.rnd.inbox.seedgen.generator.assignment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PowerLawAllocatorTest {

    @Test
    void allocate_always_sumsExactlyToTotalRows() {
        for (double concentration : new double[] {0.5, 1.0, 1.5, 2.0, 2.5}) {
            for (int n : new int[] {1, 2, 5, 30, 200}) {
                for (int totalRows : new int[] {0, 1, 3, 100, 1234}) {
                    int[] shares = PowerLawAllocator.allocate(totalRows, n, concentration);
                    assertThat(sum(shares)).as("n=%d concentration=%s totalRows=%d", n, concentration, totalRows)
                            .isEqualTo(totalRows);
                }
            }
        }
    }

    @Test
    void allocate_always_noShareIsNegative() {
        for (double concentration : new double[] {0.5, 2.5}) {
            int[] shares = PowerLawAllocator.allocate(7, 50, concentration);
            assertThat(java.util.Arrays.stream(shares).min().orElseThrow()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void allocate_higherConcentration_widensGapBetweenTopAndRest() {
        int totalRows = 10_000;
        int n = 20;

        int[] low = PowerLawAllocator.allocate(totalRows, n, 0.5);
        int[] high = PowerLawAllocator.allocate(totalRows, n, 2.5);

        // Position 0 always gets the most (weights strictly decrease by position); higher
        // concentration should make it an even larger share of the total than at low concentration.
        assertThat((double) high[0] / totalRows).isGreaterThan((double) low[0] / totalRows);
    }

    @Test
    void allocate_singleEntity_getsEverything() {
        int[] shares = PowerLawAllocator.allocate(42, 1, 1.5);

        assertThat(shares).containsExactly(42);
    }

    @Test
    void allocate_zeroTotalRows_everyoneGetsZero() {
        int[] shares = PowerLawAllocator.allocate(0, 10, 1.5);

        assertThat(shares).containsOnly(0);
    }

    @Test
    void allocate_negativeTotalRows_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> PowerLawAllocator.allocate(-1, 5, 1.5));
    }

    @Test
    void allocate_nonPositiveN_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> PowerLawAllocator.allocate(10, 0, 1.5));
    }

    @Test
    void allocate_calledTwice_producesIdenticalOutput() {
        assertThat(PowerLawAllocator.allocate(500, 17, 1.3))
                .containsExactly(PowerLawAllocator.allocate(500, 17, 1.3));
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int v : values) {
            total += v;
        }
        return total;
    }
}
