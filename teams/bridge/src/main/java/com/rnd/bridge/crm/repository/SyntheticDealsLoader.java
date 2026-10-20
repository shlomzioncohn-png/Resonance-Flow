package com.rnd.bridge.crm.repository;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rnd.bridge.crm.model.Deal;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Reads the synthetic CRM deal feed for a class that needs the raw deal list, using the same
 * {@link CrmDealJsonMapper} configuration as {@link JsonDealRepository} so the two can't drift apart.
 */
final class SyntheticDealsLoader {

    private SyntheticDealsLoader() {
    }

    static List<Deal> load(ResourceLoader resourceLoader, String datasetLocation) {
        JsonMapper mapper = CrmDealJsonMapper.create();

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
}
