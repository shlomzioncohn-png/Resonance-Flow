package com.rnd.bridge.crm.repository.hubspot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * One entry of a HubSpot property's change history. {@code value} is the property value at that
 * point in time (for {@code dealstage}, a stage id); {@code timestamp} is when it took effect.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record HubSpotPropertyHistoryEntry(String value, Instant timestamp) {
}
