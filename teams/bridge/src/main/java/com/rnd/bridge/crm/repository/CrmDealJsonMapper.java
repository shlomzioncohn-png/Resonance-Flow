package com.rnd.bridge.crm.repository;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The single Jackson configuration for reading the synthetic CRM deal feed,
 * shared by {@link JsonDealRepository} and {@link SyntheticDealsLoader} so
 * the two paths that read the same file cannot silently drift apart in how
 * they parse it.
 */
final class CrmDealJsonMapper {

    private CrmDealJsonMapper() {
    }

    static JsonMapper create() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .build();
    }
}
