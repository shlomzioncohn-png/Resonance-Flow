package com.rnd.bridge.crm.repository.hubspot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record HubSpotDealsPage(List<HubSpotDealRecord> results, HubSpotPaging paging) {
}
