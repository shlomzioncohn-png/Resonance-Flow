package com.rnd.bridge.crm.repository;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.crm.model.Deal;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * Reads the synthetic CRM deal feed with the same Jackson configuration
 * {@link JsonDealRepository} uses, so a class that needs the raw deal list
 * (currently {@link CrmDealsSeeder}) parses it identically without
 * duplicating that config out of sync. Kept separate from
 * {@code JsonDealRepository} rather than factored out of it, since that
 * class is not to be touched.
 */
final class SyntheticDealsLoader {

    private SyntheticDealsLoader() {
    }

    static List<Deal> load(ResourceLoader resourceLoader, String datasetLocation) {
        JsonMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .build();

        Resource resource = resourceLoader.getResource(datasetLocation);
        if (!resource.exists()) {
            throw new IllegalStateException("CRM synthetic dataset not found at " + datasetLocation);
        }

        SyntheticDealsFeed feed;
        try (InputStream in = resource.getInputStream()) {
            feed = mapper.readValue(in, SyntheticDealsFeed.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read CRM synthetic dataset at " + datasetLocation, e);
        }

        if (feed == null || feed.deals() == null || feed.deals().isEmpty()) {
            throw new IllegalStateException(
                    "CRM synthetic dataset at " + datasetLocation + " has no deals");
        }

        return feed.deals();
    }

    private record SyntheticDealsFeed(
            String datasetVersion,
            Instant asOf,
            String notes,
            List<Deal> deals) {
    }
}
