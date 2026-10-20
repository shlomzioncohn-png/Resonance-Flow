package com.rnd.bridge.crm.repository.salesforce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * One page of a SOQL query response - {@code camelCase} keys (contrast {@link
 * SalesforceTokenResponse}'s OAuth-spec {@code snake_case}). {@code records} stays raw {@code
 * Map<String, Object>}: this client is deal-agnostic, so field parsing belongs to the caller.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record SalesforceQueryResult(
        int totalSize,
        boolean done,
        String nextRecordsUrl,
        List<Map<String, Object>> records) {
}
