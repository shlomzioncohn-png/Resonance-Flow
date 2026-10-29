package com.rnd.bridge.crm.repository.hubspot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins that Bridge's shared, Spring-auto-configured {@link ObjectMapper}
 * stays on Jackson's default (camelCase) property-naming strategy.
 * <p>
 * That bean is not just a DTO-serialization mapper - {@link HubSpotClient}'s
 * {@code RestClient} deserializes every inbound HubSpot API response through
 * it. If anyone ever sets {@code spring.jackson.property-naming-strategy} to
 * a snake_case value in {@code application*.yml}, or registers a
 * {@code Jackson2ObjectMapperBuilderCustomizer} bean that applies one, this
 * test fails loudly - instead of what would otherwise happen: {@link
 * HubSpotDealRecord}'s camelCase fields ({@code createdAt}, {@code
 * propertiesWithHistory}) would stop matching HubSpot's actual camelCase
 * JSON keys, and because every HubSpot record type here is
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}, the mismatch would
 * not throw - the real keys would be dropped as "unknown" and the fields
 * would silently come back null (for {@code propertiesWithHistory}, that
 * then makes {@code HubSpotDealRepository.toDealHistory()} reject every
 * single deal as having "no dealstage history").
 * <p>
 * {@code JsonDealRepository} is not covered here: it reads {@code crm/synthetic-deals.json}
 * through its own isolated {@code CrmDealJsonMapper}, never this bean, so it cannot be affected
 * either way.
 * <p>
 * {@code @JsonTest} is used (rather than a plain unit test with a
 * hand-built {@code ObjectMapper}) so {@link #objectMapper} is the real
 * Spring Boot auto-configured bean, reflecting whatever
 * {@code spring.jackson.*} configuration and customizer beans actually exist
 * in this application - a locally-built mapper would prove nothing about
 * this failure mode, since the risk is entirely in how the shared bean is
 * configured.
 */
@JsonTest
class HubSpotDealRecordObjectMapperNamingStrategyTest {

    @Autowired
    private ObjectMapper objectMapper;

    private static final String DEAL_RECORD_JSON = """
            {
              "id": "D-1",
              "createdAt": "2026-05-01T08:00:00Z",
              "properties": {
                "dealname": "Acme deal"
              },
              "propertiesWithHistory": {
                "dealstage": [
                  {"value": "st-100", "timestamp": "2026-05-01T08:00:00.000Z"}
                ]
              }
            }
            """;

    @Test
    void hubSpotDealRecordCamelCaseFieldsBindFromTheSharedObjectMapper() throws Exception {
        HubSpotDealRecord record = objectMapper.readValue(DEAL_RECORD_JSON, HubSpotDealRecord.class);

        assertEquals("D-1", record.id());

        assertNotNull(record.createdAt(),
                "createdAt must bind from HubSpot's camelCase \"createdAt\" key - "
                        + "a global snake_case naming strategy on the shared ObjectMapper "
                        + "would silently null this out instead of failing");
        assertEquals(Instant.parse("2026-05-01T08:00:00Z"), record.createdAt());

        assertNotNull(record.propertiesWithHistory(),
                "propertiesWithHistory must bind from HubSpot's camelCase key - "
                        + "a global snake_case naming strategy on the shared ObjectMapper "
                        + "would silently null this out instead of failing");
        assertEquals(1, record.propertiesWithHistory().get("dealstage").size());
        assertEquals("st-100", record.propertiesWithHistory().get("dealstage").get(0).value());
    }
}
