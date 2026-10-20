package com.rnd.bridge.crm.repository.hubspot;

import com.rnd.bridge.crm.model.DealStage;

import java.util.Map;

/**
 * Result of {@link HubSpotStageMapping#resolveIdsToStages}: resolved stage ids, plus invalid ones with their reason.
 * Two maps, not a throw-on-first-bad-stage, so only deals referencing an invalid id are skipped.
 */
record StageResolution(Map<String, DealStage> idToStage, Map<String, String> invalidStageReasons) {
}
