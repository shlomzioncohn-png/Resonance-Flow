package com.rnd.bridge.crm.repository.salesforce;

import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.crm.repository.StageProbabilityBands;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves Salesforce {@code OpportunityStage} metadata to {@link DealStage} by {@code
 * IsClosed}/{@code IsWon}/{@code DefaultProbability}, never by a hardcoded label (F6 fix).
 * An unresolvable {@code StageName} is a per-record skip, handled by the caller, not here.
 */
final class SalesforceStageMapping {

    private static final String CONFIG_PREFIX = "bridge.crm.salesforce.stage-bands";

    private final StageProbabilityBands bands;

    SalesforceStageMapping(int prospectMax, int qualifiedMax, int proposalMax, int negotiationMax) {
        this.bands = new StageProbabilityBands(CONFIG_PREFIX, prospectMax, qualifiedMax, proposalMax, negotiationMax);
    }

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
        return bands.forOpenStage(probability);
    }

    private static boolean truthy(Object raw) {
        return raw instanceof Boolean value && value;
    }

    private static Integer toProbability(Object raw) {
        return raw instanceof Number number ? number.intValue() : null;
    }
}
