package com.rnd.bridge.crm.repository.hubspot;

import com.rnd.bridge.crm.model.DealStage;

import java.util.Map;

/**
 * The result of {@link HubSpotStageMapping#resolveIdsToStages}: every stage id that resolved to a
 * {@link DealStage}, plus every stage id that didn't, each with the reason it failed. Splitting
 * these into two maps (rather than throwing on the first bad stage) is what lets {@code
 * HubSpotDealRepository} skip only the deals that reference an {@code invalidStageReasons} id
 * instead of failing the entire fetch over one malformed stage.
 */
record StageResolution(Map<String, DealStage> idToStage, Map<String, String> invalidStageReasons) {
}
