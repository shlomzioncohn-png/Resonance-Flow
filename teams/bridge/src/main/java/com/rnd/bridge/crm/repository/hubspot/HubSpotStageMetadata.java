package com.rnd.bridge.crm.repository.hubspot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A HubSpot pipeline stage's {@code metadata} object - {@code isClosed}/{@code probability} - the
 * fields {@link HubSpotStageMapping} actually resolves a {@link com.rnd.bridge.crm.model.DealStage}
 * from, instead of {@code HubSpotPipelineStage#label()}.
 * <p>
 * Both fields are declared {@code String} deliberately, not {@code Boolean}/{@code BigDecimal}:
 * HubSpot's live API returns both as JSON strings ({@code "false"}, {@code "0.2"}), but Jackson's
 * default {@code String} deserializer also accepts a bare JSON boolean or number token and converts
 * it to its text form - so a portal (or a future API version) that sends {@code false}/{@code 0.2}
 * as real JSON scalars binds here identically, with no custom deserializer needed. All leniency and
 * validation of what these strings actually contain happens in {@link HubSpotStageMapping}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record HubSpotStageMetadata(String isClosed, String probability) {
}
