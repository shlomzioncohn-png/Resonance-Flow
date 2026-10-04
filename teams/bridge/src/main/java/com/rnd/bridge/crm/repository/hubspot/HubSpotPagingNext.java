package com.rnd.bridge.crm.repository.hubspot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record HubSpotPagingNext(String after) {
}
