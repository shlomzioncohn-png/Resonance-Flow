package com.rnd.bridge.crm.repository.salesforce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The OAuth 2.0 Client Credentials Flow token response - {@code snake_case} keys per the OAuth
 * spec, unlike every other Salesforce REST response this client reads (which are {@code
 * camelCase} - see {@link SalesforceQueryResult}). Explicit {@link JsonProperty} per field rather
 * than a class- or mapper-level naming strategy, so this one response shape's casing never leaks
 * into how the rest of Bridge's shared, Spring-managed {@code ObjectMapper} deserializes anything
 * else (see {@code HubSpotDealRecordObjectMapperNamingStrategyTest}'s reasoning for why a global
 * strategy change is never the right tool for a one-off wire shape).
 * <p>
 * No {@code expires_in} field - Salesforce's Client Credentials/JWT Bearer flows don't return one,
 * which is why {@code SalesforceClient} caches this token and refreshes reactively on a 401
 * instead of proactively on a TTL (see {@code SalesforceClient}'s Javadoc).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record SalesforceTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("instance_url") String instanceUrl,
        @JsonProperty("token_type") String tokenType) {
}
