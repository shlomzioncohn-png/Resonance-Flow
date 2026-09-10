package com.rnd.bridge.crm.repository.hubspot;

import com.rnd.bridge.crm.model.DealStage;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves HubSpot pipeline stages to {@link DealStage}.
 * <p>
 * Stage <em>ids</em> are opaque and differ per HubSpot portal, so they are
 * never hardcoded here. What is stable enough to hardcode is the stage
 * <em>label</em> of HubSpot's default sales pipeline: Appointment Scheduled,
 * Qualified To Buy, Presentation Scheduled, Decision Maker Bought-In,
 * Contract Sent, Closed Won, Closed Lost. Each fetched pipeline's stages are
 * matched against those labels (trimmed, case-insensitive) to build an
 * id-to-{@link DealStage} table for the ids this portal actually uses.
 * <p>
 * {@link DealStage} has six non-{@code ON_HOLD} values against HubSpot's
 * seven default stages, so two stages collapse onto one:
 * <ul>
 *   <li>{@code Appointment Scheduled} -&gt; {@code PROSPECT} - first contact.</li>
 *   <li>{@code Qualified To Buy} -&gt; {@code QUALIFIED}.</li>
 *   <li>{@code Presentation Scheduled} -&gt; {@code PROPOSAL} - we've put
 *       something concrete in front of the buyer.</li>
 *   <li>{@code Decision Maker Bought-In} -&gt; {@code NEGOTIATION} and
 *       {@code Contract Sent} -&gt; {@code NEGOTIATION} - both are
 *       late-stage, pre-close, terms-being-worked-out states; the domain
 *       model only has one bucket for that, so both collapse into it rather
 *       than inventing a stage HubSpot doesn't have a matching concept for.</li>
 *   <li>{@code Closed Won} -&gt; {@code CLOSED_WON}, {@code Closed Lost} -&gt;
 *       {@code CLOSED_LOST}.</li>
 * </ul>
 * {@link DealStage#ON_HOLD} has no HubSpot equivalent and is intentionally
 * absent from this table: this repository never produces it, rather than
 * inventing a stage to fill the gap.
 * <p>
 * A pipeline stage whose label matches none of the above fails loudly
 * ({@link UnknownHubSpotStageException}) instead of being dropped or
 * defaulted to something plausible.
 */
final class HubSpotStageMapping {

    private static final Map<String, DealStage> LABEL_TO_STAGE = buildLabelToStage();

    private static Map<String, DealStage> buildLabelToStage() {
        Map<String, DealStage> map = new HashMap<>();
        map.put("appointment scheduled", DealStage.PROSPECT);
        map.put("qualified to buy", DealStage.QUALIFIED);
        map.put("presentation scheduled", DealStage.PROPOSAL);
        map.put("decision maker bought-in", DealStage.NEGOTIATION);
        map.put("contract sent", DealStage.NEGOTIATION);
        map.put("closed won", DealStage.CLOSED_WON);
        map.put("closed lost", DealStage.CLOSED_LOST);
        return Map.copyOf(map);
    }

    private HubSpotStageMapping() {
    }

    /**
     * Builds the id-to-{@link DealStage} table for every stage of every
     * given pipeline, by label. Throws {@link UnknownHubSpotStageException}
     * on the first stage whose label isn't recognised.
     */
    static Map<String, DealStage> resolveIdsToStages(List<HubSpotPipeline> pipelines) {
        Map<String, DealStage> idToStage = new HashMap<>();
        for (HubSpotPipeline pipeline : pipelines) {
            if (pipeline.stages() == null) {
                continue;
            }
            for (HubSpotPipelineStage stage : pipeline.stages()) {
                DealStage mapped = LABEL_TO_STAGE.get(normalize(stage.label()));
                if (mapped == null) {
                    throw new UnknownHubSpotStageException(
                            "HubSpot pipeline \"" + pipeline.label() + "\" (id " + pipeline.id()
                                    + ") has a stage \"" + stage.label() + "\" (id " + stage.id()
                                    + ") with no known DealStage mapping - add it to HubSpotStageMapping "
                                    + "or confirm it is genuinely out of scope");
                }
                idToStage.put(stage.id(), mapped);
            }
        }
        return Map.copyOf(idToStage);
    }

    private static String normalize(String label) {
        return label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
    }
}
