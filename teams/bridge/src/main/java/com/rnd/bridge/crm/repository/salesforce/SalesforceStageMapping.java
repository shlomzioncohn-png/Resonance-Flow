package com.rnd.bridge.crm.repository.salesforce;

import com.rnd.bridge.crm.model.DealStage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves Salesforce {@code OpportunityStage} metadata to {@link DealStage} - by stage
 * <em>metadata</em> ({@code IsClosed}/{@code IsWon}/{@code DefaultProbability}), never by
 * matching a hardcoded label string the way {@code HubSpotStageMapping} does. See
 * {@code teams/bridge/docs/crm-salesforce-design.md} §3 for the full rationale - in short, this
 * is what actually fixes the HubSpot audit finding F6 (a renamed/custom stage label breaking the
 * whole sync): every stage, recognized before or not, carries this metadata, so there is no
 * "unrecognized stage" state for this class to fail on.
 * <p>
 * {@link #resolveLabelsToStages(List)} is re-run fresh on every call, from {@code
 * OpportunityStage} rows {@code SalesforceDealRepository} fetched that same call - never cached -
 * so a stage added or renamed in the org is picked up on the very next {@code snapshot()}/{@code
 * fetchDeal()}. Unlike {@code HubSpotStageMapping}'s pipeline-label check, there is no
 * construction-time validation of the fetched metadata and no "fail the whole sync" case: a
 * {@code StageName} on an {@code Opportunity} row that isn't present in the resolved map is a
 * per-record skip, handled by {@code SalesforceDealRepository}, not by this class.
 * <p>
 * {@code IsClosed=true,IsWon=true} -&gt; {@link DealStage#CLOSED_WON};
 * {@code IsClosed=true,IsWon=false} -&gt; {@link DealStage#CLOSED_LOST}. An open stage
 * ({@code IsClosed=false}) is bucketed by {@code DefaultProbability} into one of four
 * configurable bands (defaults: 0-20 {@link DealStage#PROSPECT}, 21-50
 * {@link DealStage#QUALIFIED}, 51-80 {@link DealStage#PROPOSAL}, 81-99
 * {@link DealStage#NEGOTIATION}) - a {@code null} probability on an open stage falls back to the
 * most conservative bucket, {@code PROSPECT}. {@link DealStage#ON_HOLD} has no Salesforce
 * equivalent and this class never produces it.
 * <p>
 * The four band boundaries are validated at construction - strictly increasing and partitioning
 * {@code 0..99} exactly, no gap, no overlap - so a misconfiguration fails loudly at startup
 * instead of silently misclassifying every open deal.
 */
final class SalesforceStageMapping {

    private final int prospectMax;
    private final int qualifiedMax;
    private final int proposalMax;
    private final int negotiationMax;

    SalesforceStageMapping(int prospectMax, int qualifiedMax, int proposalMax, int negotiationMax) {
        if (prospectMax < 0) {
            throw new IllegalStateException(
                    "bridge.crm.salesforce.stage-bands.prospect-max must be >= 0, was " + prospectMax);
        }
        if (qualifiedMax <= prospectMax) {
            throw new IllegalStateException(
                    "bridge.crm.salesforce.stage-bands.qualified-max (" + qualifiedMax
                            + ") must be greater than prospect-max (" + prospectMax + ")");
        }
        if (proposalMax <= qualifiedMax) {
            throw new IllegalStateException(
                    "bridge.crm.salesforce.stage-bands.proposal-max (" + proposalMax
                            + ") must be greater than qualified-max (" + qualifiedMax + ")");
        }
        if (negotiationMax <= proposalMax) {
            throw new IllegalStateException(
                    "bridge.crm.salesforce.stage-bands.negotiation-max (" + negotiationMax
                            + ") must be greater than proposal-max (" + proposalMax + ")");
        }
        if (negotiationMax != 99) {
            throw new IllegalStateException(
                    "bridge.crm.salesforce.stage-bands.negotiation-max must be exactly 99, so the bands "
                            + "cover the full 0-99 probability range with no gap at the top - was "
                            + negotiationMax);
        }
        this.prospectMax = prospectMax;
        this.qualifiedMax = qualifiedMax;
        this.proposalMax = proposalMax;
        this.negotiationMax = negotiationMax;
    }

    /**
     * Builds the label-to-{@link DealStage} table for every given {@code OpportunityStage} row.
     * Rows are shaped like {@link SalesforceClient#query}'s raw output: {@code MasterLabel}
     * (String), {@code IsClosed}/{@code IsWon} (Boolean), {@code DefaultProbability} (a {@code
     * Number}, or absent/null for an open stage with no default set). A row missing {@code
     * MasterLabel} is skipped - defensive only, Salesforce always returns it.
     */
    Map<String, DealStage> resolveLabelsToStages(List<Map<String, Object>> stageRows) {
        Map<String, DealStage> labelToStage = new HashMap<>();
        for (Map<String, Object> row : stageRows) {
            if (!(row.get("MasterLabel") instanceof String masterLabel)) {
                continue;
            }
            boolean isClosed = truthy(row.get("IsClosed"));
            boolean isWon = truthy(row.get("IsWon"));
            labelToStage.put(masterLabel, resolve(isClosed, isWon, row.get("DefaultProbability")));
        }
        return Map.copyOf(labelToStage);
    }

    private DealStage resolve(boolean isClosed, boolean isWon, Object defaultProbabilityRaw) {
        if (isClosed) {
            return isWon ? DealStage.CLOSED_WON : DealStage.CLOSED_LOST;
        }
        Integer probability = toProbability(defaultProbabilityRaw);
        if (probability == null) {
            return DealStage.PROSPECT;
        }
        if (probability <= prospectMax) {
            return DealStage.PROSPECT;
        }
        if (probability <= qualifiedMax) {
            return DealStage.QUALIFIED;
        }
        if (probability <= proposalMax) {
            return DealStage.PROPOSAL;
        }
        return DealStage.NEGOTIATION;
    }

    private static boolean truthy(Object raw) {
        return raw instanceof Boolean value && value;
    }

    private static Integer toProbability(Object raw) {
        return raw instanceof Number number ? number.intValue() : null;
    }
}
