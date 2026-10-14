package com.rnd.bridge.crm.repository;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rnd.bridge.crm.model.Deal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
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
 * The dataset is the adapter's actual runtime data source - there is no live CRM in scope for
 * this program - so it is read eagerly at construction time and failures are fatal at startup
 * rather than surfacing lazily per call. The fixture never changes at runtime, so one {@link
 * DealSnapshot} built once at construction correctly serves every {@link #snapshot()} call - there
 * is no cross-call staleness to guard against here the way there is in {@code
 * HubSpotDealRepository}.
 */
@Repository
public class JsonDealRepository implements DealRepository {

    private final DealSnapshot snapshot;

    public JsonDealRepository(
            ResourceLoader resourceLoader,
            @Value("${bridge.crm.dataset:classpath:crm/synthetic-deals.json}") String datasetLocation) {

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

        if (feed == null || feed.asOf() == null) {
            throw new IllegalStateException(
                    "CRM synthetic dataset at " + datasetLocation + " is missing as_of");
        }
        if (feed.deals() == null || feed.deals().isEmpty()) {
            throw new IllegalStateException(
                    "CRM synthetic dataset at " + datasetLocation + " has no deals");
        }

        List<Deal> sorted = new ArrayList<>(feed.deals());
        sorted.sort(Comparator.comparing(Deal::stageChangedAt).thenComparing(Deal::dealId));
        List<Deal> allDeals = Collections.unmodifiableList(sorted);

        Map<String, List<Deal>> history = new LinkedHashMap<>();
        for (Deal deal : allDeals) {
            history.computeIfAbsent(deal.dealId(), id -> new ArrayList<>()).add(deal);
        }
        Map<String, List<Deal>> unmodifiableHistory = new LinkedHashMap<>();
        for (Map.Entry<String, List<Deal>> entry : history.entrySet()) {
            unmodifiableHistory.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        List<String> dealIds = Collections.unmodifiableList(new ArrayList<>(unmodifiableHistory.keySet()));

        this.snapshot = new DealSnapshot(
                feed.asOf(), allDeals, Collections.unmodifiableMap(unmodifiableHistory), dealIds, 0);
    }

    @Override
    public DealSnapshot snapshot() {
        return snapshot;
    }

    /**
     * The fixture is loaded once at construction (see the class Javadoc) and never changes at
     * runtime, so "fresh" here means the same thing it does for {@link #snapshot()}: there is no
     * live source to re-read, just the one dataset read at startup.
     */
    @Override
    public Optional<Deal> fetchDeal(String dealId) {
        if (dealId == null || dealId.isBlank()) {
            throw new IllegalArgumentException("dealId must not be blank");
        }
        return snapshot.findLatestByDealId(dealId);
    }
}
