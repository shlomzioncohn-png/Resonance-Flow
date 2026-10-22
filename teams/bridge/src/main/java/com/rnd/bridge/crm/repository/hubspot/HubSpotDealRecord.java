package com.rnd.bridge.crm.repository.hubspot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One deal as returned by {@code GET /crm/v3/objects/deals}. {@code properties} is current state;
 * {@code propertiesWithHistory} is that property's change history, newest-first.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record HubSpotDealRecord(
        String id,
        Instant createdAt,
        Map<String, String> properties,
        Map<String, List<HubSpotPropertyHistoryEntry>> propertiesWithHistory) {
}
