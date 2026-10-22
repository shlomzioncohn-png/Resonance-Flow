package com.rnd.bridge.crm.repository.salesforce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The OAuth 2.0 token response - {@code snake_case} keys per spec (contrast {@link
 * SalesforceQueryResult}'s {@code camelCase}), via explicit {@link JsonProperty} per field so this
 * one shape's casing never leaks into Bridge's shared {@code ObjectMapper}. No {@code expires_in} - see {@code SalesforceClient}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record SalesforceTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("instance_url") String instanceUrl,
        @JsonProperty("token_type") String tokenType) {
}
