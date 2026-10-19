package com.rnd.bridge.crm.repository.salesforce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * One page of a SOQL query response - {@code camelCase} keys, Salesforce's normal REST casing
 * (contrast {@link SalesforceTokenResponse}'s OAuth-spec {@code snake_case}). {@code records} is
 * deliberately left as raw {@code Map<String, Object>} rows rather than a typed record: this
 * client is deal-agnostic (it runs whatever SOQL string {@code SalesforceDealRepository} gives
 * it), so field-specific parsing belongs there, not here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record SalesforceQueryResult(
        int totalSize,
        boolean done,
        String nextRecordsUrl,
        List<Map<String, Object>> records) {
}
