package com.rnd.chain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriorityScoreServiceTest {

    private final PriorityScoreService service = new PriorityScoreService();

    @Test
    void mindScoreNull_returnsNull() {
        assertThat(service.compute(null, 0.9, 0, 0, 0)).isNull();
    }

    @Test
    void mindScoreNull_returnsNull_regardlessOfOtherInputs() {
        assertThat(service.compute(null, null, 5.0, 3, 2.0)).isNull();
    }

    @Test
    void noBlockersNoConstraintsNoDependents_isDrivenEntirelyByMind() {
        // s = 0.4*(1-0) + 0.3*(1-0) + 0.2*0 + 0.1*(1*1) = 0.8 -> 1 + 9*0.8 = 8.2
        assertThat(service.compute(1.0, 1.0, 0, 0, 0)).isEqualTo(8.2);
    }

    @Test
    void zeroMindScoreNoFeasibilityPenalty_isTheWeightsFloor() {
        // s = 0.4 + 0.3 + 0.2*0 + 0.1*0 = 0.7 -> 1 + 9*0.7 = 7.3
        assertThat(service.compute(0.0, 1.0, 0, 0, 0)).isEqualTo(7.3);
    }

    @Test
    void higherConfidenceBlocker_lowersScoreMoreThanLowerConfidenceBlocker() {
        Double lowConfidenceBlocker = service.compute(0.8, 0.9, 0.3, 0, 0);
        Double highConfidenceBlocker = service.compute(0.8, 0.9, 0.9, 0, 0);

        assertThat(highConfidenceBlocker).isLessThan(lowConfidenceBlocker);
    }

    @Test
    void higherConfidenceDependent_raisesScoreMoreThanLowerConfidenceDependent() {
        Double lowConfidenceDependent = service.compute(0.5, 1.0, 0, 0, 0.2);
        Double highConfidenceDependent = service.compute(0.5, 1.0, 0, 0, 0.8);

        assertThat(highConfidenceDependent).isGreaterThan(lowConfidenceDependent);
    }

    @Test
    void confidenceNull_defaultsToFullConfidence() {
        assertThat(service.compute(0.6, null, 0, 0, 0))
                .isEqualTo(service.compute(0.6, 1.0, 0, 0, 0));
    }

    @Test
    void result_isAlwaysInOneToTenRange_evenUnderHeavyBlocking() {
        Double heavilyBlocked = service.compute(1.0, 1.0, 1000.0, 500, 0);

        assertThat(heavilyBlocked).isBetween(1.0, 10.0);
        // Unlike an earlier draft of this formula (divide-then-clamp), heavy blocking doesn't
        // collapse every task to the same floor value - a lightly-blocked and a heavily-blocked
        // task still rank differently instead of both saturating to the same floor.
        Double lightlyBlocked = service.compute(1.0, 1.0, 1.0, 0, 0);
        assertThat(heavilyBlocked).isLessThan(lightlyBlocked);
    }

    @Test
    void result_isRoundedToTwoDecimalPlaces() {
        Double result = service.compute(0.37, 0.61, 1.3, 2, 0.4);

        double scaledByHundred = result * 100;
        assertThat(scaledByHundred).isCloseTo(Math.round(scaledByHundred), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void mindScoreOutOfRange_throws() {
        assertThatThrownBy(() -> service.compute(1.5, 1.0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mindScore");
    }

    @Test
    void confidenceOutOfRange_throws() {
        assertThatThrownBy(() -> service.compute(0.5, -0.1, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void negativeBlockingConfidenceSum_throws() {
        assertThatThrownBy(() -> service.compute(0.5, 1.0, -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockingConfidenceSum");
    }

    @Test
    void negativeOpenConstraintCount_throws() {
        assertThatThrownBy(() -> service.compute(0.5, 1.0, 0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openConstraintCount");
    }

    @Test
    void negativeDependentConfidenceSum_throws() {
        assertThatThrownBy(() -> service.compute(0.5, 1.0, 0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dependentConfidenceSum");
    }
}
