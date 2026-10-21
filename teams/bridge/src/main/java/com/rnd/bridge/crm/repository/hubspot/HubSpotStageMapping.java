package com.rnd.bridge.crm.repository.hubspot;

import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.crm.repository.StageProbabilityBands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves HubSpot pipeline stages to {@link DealStage} by stage {@code isClosed}/{@code
 * probability} metadata, never by matching the stage label (F6 fix). A stage that can't be
 * resolved is a per-record skip, not a whole-sync failure, unless zero/every stage is invalid.
 */
final class HubSpotStageMapping {

    private static final Logger log = LoggerFactory.getLogger(HubSpotStageMapping.class);

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final StageProbabilityBands bands;

    HubSpotStageMapping(int prospectMax, int qualifiedMax, int proposalMax, int negotiationMax) {
        this.bands = new StageProbabilityBands(
                "bridge.crm.hubspot.stage-bands", prospectMax, qualifiedMax, proposalMax, negotiationMax);
    }

    StageResolution resolveIdsToStages(List<HubSpotPipeline> pipelines) {
        Map<String, DealStage> idToStage = new HashMap<>();
        Map<String, String> invalidStageReasons = new HashMap<>();
        int totalStages = 0;
        for (HubSpotPipeline pipeline : pipelines) {
            if (pipeline.stages() == null) {
                continue;
            }
            for (HubSpotPipelineStage stage : pipeline.stages()) {
                totalStages++;
                if (stage.id() == null || stage.id().isBlank()) {
                    throw new UnknownHubSpotStageException(
                            "HubSpot pipeline \"" + pipeline.label() + "\" (id " + pipeline.id()
                                    + ") has a stage \"" + stage.label() + "\" with no stage id - "
                                    + "cannot be matched against any deal's dealstage");
                }
                Outcome outcome = classify(pipeline, stage);
                if (outcome.stage() != null) {
                    idToStage.put(stage.id(), outcome.stage());
                } else {
                    invalidStageReasons.put(stage.id(), outcome.invalidReason());
                }
            }
        }
        if (totalStages == 0) {
            throw new UnknownHubSpotStageException("HubSpot returned no pipeline stages at all");
        }
        if (idToStage.isEmpty()) {
            throw new UnknownHubSpotStageException(
                    "Every one of the " + totalStages + " fetched HubSpot stage(s) failed to resolve to a "
                            + "DealStage - likely a HubSpot API shape change, not one bad stage: "
                            + invalidStageReasons);
        }
        return new StageResolution(Map.copyOf(idToStage), Map.copyOf(invalidStageReasons));
    }

    private Outcome classify(HubSpotPipeline pipeline, HubSpotPipelineStage stage) {
        HubSpotStageMetadata metadata = stage.metadata();
        if (metadata == null) {
            return Outcome.invalid("no metadata on this stage");
        }
        Boolean isClosed = parseBoolean(metadata.isClosed());
        if (isClosed == null) {
            return Outcome.invalid(
                    "isClosed is missing or not a recognized boolean value (\"" + metadata.isClosed() + "\")");
        }
        // Missing probability (null) defaults safely; a present-but-garbage value does not.
        String rawProbability = metadata.probability();
        boolean probabilityKeyAbsent = rawProbability == null;
        BigDecimal probability = probabilityKeyAbsent ? null : parseProbability(rawProbability);
        boolean probabilityInvalid = !probabilityKeyAbsent && probability == null;

        if (isClosed) {
            if (probabilityKeyAbsent) {
                return Outcome.invalid("closed stage has no probability to distinguish won from lost");
            }
            if (probabilityInvalid) {
                return Outcome.invalid(
                        "closed stage probability is not a valid number in [0,1] (\"" + rawProbability + "\")");
            }
            if (probability.compareTo(ONE) == 0) {
                return Outcome.valid(DealStage.CLOSED_WON);
            }
            if (probability.compareTo(ZERO) == 0) {
                return Outcome.valid(DealStage.CLOSED_LOST);
            }
            return Outcome.invalid(
                    "closed stage probability \"" + rawProbability + "\" is neither 0 nor 1 - cannot tell won from lost");
        }

        if (probabilityKeyAbsent) {
            log.warn("HubSpot pipeline {} stage {} (\"{}\") is open with no probability - defaulting to PROSPECT",
                    pipeline.id(), stage.id(), stage.label());
            return Outcome.valid(DealStage.PROSPECT);
        }
        if (probabilityInvalid) {
            return Outcome.invalid(
                    "open stage probability is not a valid number in [0,1] (\"" + rawProbability + "\")");
        }
        int percent = probability.multiply(ONE_HUNDRED).setScale(0, RoundingMode.HALF_UP).intValueExact();
        return Outcome.valid(bands.forOpenStage(percent));
    }

    private static Boolean parseBoolean(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static BigDecimal parseProbability(String raw) {
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (value.compareTo(ZERO) < 0 || value.compareTo(ONE) > 0) {
            return null;
        }
        return value;
    }

    private record Outcome(DealStage stage, String invalidReason) {
        static Outcome valid(DealStage stage) {
            return new Outcome(stage, null);
        }

        static Outcome invalid(String reason) {
            return new Outcome(null, reason);
        }
    }
}
