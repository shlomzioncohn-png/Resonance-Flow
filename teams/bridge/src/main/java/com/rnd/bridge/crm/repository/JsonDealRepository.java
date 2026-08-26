package com.rnd.bridge.crm.repository;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.crm.model.Deal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the synthetic CRM deal feed from a JSON resource.
 * <p>
 * The dataset is the adapter's actual runtime data source - there is no live
 * CRM in scope for this program - so it is read eagerly at construction time
 * and failures are fatal at startup rather than surfacing lazily per call.
 */
@Repository
public class JsonDealRepository implements DealRepository {

    private final Instant asOf;
    private final List<Deal> allDeals;
    private final Map<String, List<Deal>> historyByDealId;
    private final List<String> dealIds;

    public JsonDealRepository(
            ResourceLoader resourceLoader,
            @Value("${bridge.crm.dataset:classpath:crm/synthetic-deals.json}") String datasetLocation) {

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

        if (feed == null || feed.asOf() == null) {
            throw new IllegalStateException(
                    "CRM synthetic dataset at " + datasetLocation + " is missing as_of");
        }
        if (feed.deals() == null || feed.deals().isEmpty()) {
            throw new IllegalStateException(
                    "CRM synthetic dataset at " + datasetLocation + " has no deals");
        }

        this.asOf = feed.asOf();

        List<Deal> sorted = new ArrayList<>(feed.deals());
        sorted.sort(Comparator.comparing(Deal::stageChangedAt).thenComparing(Deal::dealId));
        this.allDeals = Collections.unmodifiableList(sorted);

        Map<String, List<Deal>> history = new LinkedHashMap<>();
        for (Deal deal : sorted) {
            history.computeIfAbsent(deal.dealId(), id -> new ArrayList<>()).add(deal);
        }
        Map<String, List<Deal>> unmodifiableHistory = new LinkedHashMap<>();
        for (Map.Entry<String, List<Deal>> entry : history.entrySet()) {
            unmodifiableHistory.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        this.historyByDealId = Collections.unmodifiableMap(unmodifiableHistory);
        this.dealIds = Collections.unmodifiableList(new ArrayList<>(unmodifiableHistory.keySet()));
    }

    @Override
    public Instant asOf() {
        return asOf;
    }

    @Override
    public List<Deal> findAll() {
        return allDeals;
    }

    @Override
    public List<Deal> findHistoryByDealId(String dealId) {
        return historyByDealId.getOrDefault(dealId, List.of());
    }

    @Override
    public Optional<Deal> findLatestByDealId(String dealId) {
        List<Deal> history = findHistoryByDealId(dealId);
        if (history.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(history.get(history.size() - 1));
    }

    @Override
    public List<String> findAllDealIds() {
        return dealIds;
    }

    private record SyntheticDealsFeed(
            String datasetVersion,
            Instant asOf,
            String notes,
            List<Deal> deals) {
    }
}
