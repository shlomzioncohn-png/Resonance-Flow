package com.rnd.bridge.crm.repository;

import com.rnd.bridge.crm.model.DealStage;

/**
 * Buckets an open stage's 0-99 probability percentage into a {@link DealStage}, shared by every
 * CRM whose stage metadata carries one. Boundaries are validated at construction - strictly increasing, partitioning 0..99 exactly.
 */
public final class StageProbabilityBands {

    private final int prospectMax;
    private final int qualifiedMax;
    private final int proposalMax;
    private final int negotiationMax;

    public StageProbabilityBands(
            String configPrefix, int prospectMax, int qualifiedMax, int proposalMax, int negotiationMax) {
        if (prospectMax < 0) {
            throw new IllegalStateException(
                    configPrefix + ".prospect-max must be >= 0, was " + prospectMax);
        }
        if (qualifiedMax <= prospectMax) {
            throw new IllegalStateException(
                    configPrefix + ".qualified-max (" + qualifiedMax
                            + ") must be greater than prospect-max (" + prospectMax + ")");
        }
        if (proposalMax <= qualifiedMax) {
            throw new IllegalStateException(
                    configPrefix + ".proposal-max (" + proposalMax
                            + ") must be greater than qualified-max (" + qualifiedMax + ")");
        }
        if (negotiationMax <= proposalMax) {
            throw new IllegalStateException(
                    configPrefix + ".negotiation-max (" + negotiationMax
                            + ") must be greater than proposal-max (" + proposalMax + ")");
        }
        if (negotiationMax != 99) {
            throw new IllegalStateException(
                    configPrefix + ".negotiation-max must be exactly 99, so the bands "
                            + "cover the full 0-99 probability range with no gap at the top - was "
                            + negotiationMax);
        }
        this.prospectMax = prospectMax;
        this.qualifiedMax = qualifiedMax;
        this.proposalMax = proposalMax;
        this.negotiationMax = negotiationMax;
    }

    public DealStage forOpenStage(int percent) {
        if (percent <= prospectMax) {
            return DealStage.PROSPECT;
        }
        if (percent <= qualifiedMax) {
            return DealStage.QUALIFIED;
        }
        if (percent <= proposalMax) {
            return DealStage.PROPOSAL;
        }
        return DealStage.NEGOTIATION;
    }
}
