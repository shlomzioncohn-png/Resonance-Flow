package com.rnd.bridge.crm.repository.hubspot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One stage of a HubSpot pipeline; {@code id} is the opaque value in a deal's {@code dealstage} property.
 * {@code label} is purely informational now - {@link HubSpotStageMapping} resolves from {@code metadata}, never label (F6).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record HubSpotPipelineStage(String id, String label, HubSpotStageMetadata metadata) {
}
