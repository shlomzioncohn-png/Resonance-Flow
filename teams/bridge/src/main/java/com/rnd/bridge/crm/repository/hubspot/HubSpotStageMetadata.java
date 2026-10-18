package com.rnd.bridge.crm.repository.hubspot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A HubSpot pipeline stage's {@code metadata} ({@code isClosed}/{@code probability}) - what {@link
 * HubSpotStageMapping} resolves a {@link com.rnd.bridge.crm.model.DealStage} from, not label.
 * Both fields are {@code String}: HubSpot sends them as JSON strings, but a bare boolean/number token binds identically.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record HubSpotStageMetadata(String isClosed, String probability) {
}
