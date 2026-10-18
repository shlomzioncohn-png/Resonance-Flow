package com.rnd.bridge.crm.repository.hubspot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One stage of a HubSpot pipeline. {@code id} is the opaque, portal-specific
 * value that shows up as a deal's {@code dealstage} property. {@code label}
 * is the human-readable name - purely informational now (log/error messages
 * only); {@link HubSpotStageMapping} resolves a {@link com.rnd.bridge.crm.model.DealStage}
 * from {@code metadata}, never from this field (audit finding F6: a renamed
 * or custom label must not break the mapping).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record HubSpotPipelineStage(String id, String label, HubSpotStageMetadata metadata) {
}
