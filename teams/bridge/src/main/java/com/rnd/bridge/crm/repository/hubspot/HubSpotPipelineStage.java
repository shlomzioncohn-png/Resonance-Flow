package com.rnd.bridge.crm.repository.hubspot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One stage of a HubSpot pipeline. {@code id} is the opaque, portal-specific
 * value that shows up as a deal's {@code dealstage} property; {@code label}
 * is the human-readable name this repository actually matches against, via
 * {@link HubSpotStageMapping}, to resolve a {@link com.rnd.bridge.crm.model.DealStage}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record HubSpotPipelineStage(String id, String label) {
}
