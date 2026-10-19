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
 * Resolves HubSpot pipeline stages to {@link DealStage} by stage <em>metadata</em>
 * ({@code isClosed}/{@code probability}), never by matching the stage <em>label</em> - this is the
 * actual fix for CRM-lane audit finding F6 (a renamed or custom stage label breaking the whole
 * sync). {@code label} is now purely informational, used only in log/error messages.
 * <p>
 * HubSpot's metadata has no explicit won/lost flag (confirmed against the live API - unlike
 * Salesforce's {@code IsWon}): {@code isClosed=true} with {@code probability} exactly {@code 1.0}
 * is {@link DealStage#CLOSED_WON}, exactly {@code 0.0} is {@link DealStage#CLOSED_LOST}. Any other
 * closed-stage probability (missing, non-boundary, unparseable) can't be disambiguated and is
 * invalid. An open stage ({@code isClosed=false}) is bucketed by {@code probability} into one of
 * four configurable {@link StageProbabilityBands} (defaults 20/50/70/99 - intentionally not
 * Salesforce's 20/50/80/99, so HubSpot's own default 7-stage pipeline keeps mapping exactly like it
 * did under label matching); a missing probability on an open stage defaults to the most
 * conservative bucket, {@link DealStage#PROSPECT}, logged once per stage per run. {@link
 * DealStage#ON_HOLD} has no HubSpot equivalent and this class never produces it.
 * <p>
 * {@code isClosed}/{@code probability} both arrive as strings on the live API ({@code "false"},
 * {@code "0.2"}) - {@link #parseBoolean} accepts only {@code "true"}/{@code "false"}
 * (case-insensitive) and {@link #parseProbability} parses via {@link BigDecimal}, never
 * {@code Double}: {@code BigDecimal}'s string constructor has no NaN/Infinity representation at
 * all (unlike {@code Double.parseDouble("NaN")}, which succeeds), so a non-finite value is rejected
 * structurally, not by a separate finiteness check, and {@code BigDecimal.compareTo} (never
 * {@code equals}) is used for the {@code 0}/{@code 1} boundary check so {@code "1.0"} and {@code
 * "1.00"} both equal {@code BigDecimal.ONE} - {@code "1.0".equals(BigDecimal.ONE)} is {@code
 * false}, but {@code compareTo} is {@code 0}.
 * <p>
 * {@link #resolveIdsToStages} never throws for a single bad stage - that stage's id and the reason
 * it failed go into the returned {@link StageResolution#invalidStageReasons()} instead, so {@code
 * HubSpotDealRepository} can skip only the deals that actually reference it (see that class's
 * constructor and {@code snapshot()} for what happens with an invalid stage - a WARN at
 * resolution time, then a per-deal skip at read time for any deal that lands on it). It throws
 * {@link UnknownHubSpotStageException} only for problems severe enough that no individual deal
 * could ever be isolated from them: a stage with no id (the only key a deal's {@code dealstage}
 * could be matched against), zero stages fetched at all, or every single fetched stage turning out
 * invalid (overwhelmingly more likely a HubSpot API shape change than every stage genuinely being
 * malformed) - the same real-world signal as the "15 deals stuck on one bad stage" threshold {@code
 * HubSpotDealRepository} enforces afterward, just at zero deals instead of some ratio of them.
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
        // A missing probability (the JSON key itself absent, metadata.probability() == null) and a
        // present-but-garbage one ("", "abc", "NaN", -0.1, 1.5) are deliberately NOT the same case:
        // only the former has a safe default on an open stage. A present empty string is a value
        // someone sent, not an absent one, so it falls into the "invalid, never defaulted" bucket
        // below exactly like "abc" does, even on an open stage.
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
