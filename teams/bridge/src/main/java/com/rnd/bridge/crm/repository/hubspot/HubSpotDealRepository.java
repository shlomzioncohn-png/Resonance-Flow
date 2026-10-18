package com.rnd.bridge.crm.repository.hubspot;

import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.crm.repository.DealRepository;
import com.rnd.bridge.crm.repository.DealSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the CRM deal feed from the live HubSpot API, one {@link Deal} per {@code dealstage}
 * history entry. Stages resolve by {@code isClosed}/{@code probability} metadata, never by label
 * (see {@link HubSpotStageMapping}); every {@link Deal} carries sourceRef {@code "crm:hubspot:<id>"}.
 */
@Repository
@Profile("hubspot")
public class HubSpotDealRepository implements DealRepository {

    private static final Logger log = LoggerFactory.getLogger(HubSpotDealRepository.class);

    private static final String SOURCE_REF_PREFIX = "crm:hubspot:";

    private final HubSpotClient client;
    private final Clock clock;
    private final HubSpotStageMapping stageMapping;
    private final double maxSkippedRatio;

    @Autowired
    public HubSpotDealRepository(
            RestClient.Builder restClientBuilder,
            @Value("${bridge.crm.hubspot.base-url:https://api.hubspot.com}") String baseUrl,
            @Value("${bridge.crm.hubspot.token}") String token,
            @Value("${bridge.crm.hubspot.page-size:100}") int pageSize,
            @Value("${bridge.crm.hubspot.max-retries:5}") int maxRetries,
            @Value("${bridge.crm.hubspot.initial-backoff-millis:500}") long initialBackoffMillis,
            @Value("${bridge.crm.hubspot.max-backoff-millis:8000}") long maxBackoffMillis,
            @Value("${bridge.crm.hubspot.connect-timeout-millis:5000}") long connectTimeoutMillis,
            @Value("${bridge.crm.hubspot.read-timeout-millis:15000}") long readTimeoutMillis,
            @Value("${bridge.crm.hubspot.stage-bands.prospect-max:20}") int prospectMax,
            @Value("${bridge.crm.hubspot.stage-bands.qualified-max:50}") int qualifiedMax,
            @Value("${bridge.crm.hubspot.stage-bands.proposal-max:70}") int proposalMax,
            @Value("${bridge.crm.hubspot.stage-bands.negotiation-max:99}") int negotiationMax,
            @Value("${bridge.crm.hubspot.max-skipped-ratio:0.5}") double maxSkippedRatio) {
        this(HubSpotClient.production(restClientBuilder, baseUrl, token, pageSize, maxRetries,
                initialBackoffMillis, maxBackoffMillis, connectTimeoutMillis, readTimeoutMillis),
                Clock.systemUTC(),
                new HubSpotStageMapping(prospectMax, qualifiedMax, proposalMax, negotiationMax),
                maxSkippedRatio);
    }

    HubSpotDealRepository(
            HubSpotClient client, Clock clock, HubSpotStageMapping stageMapping, double maxSkippedRatio) {
        if (!(maxSkippedRatio > 0 && maxSkippedRatio <= 1)) {
            throw new IllegalStateException(
                    "bridge.crm.hubspot.max-skipped-ratio must be > 0 and <= 1, was " + maxSkippedRatio);
        }
        this.client = client;
        this.clock = clock;
        this.stageMapping = stageMapping;
        this.maxSkippedRatio = maxSkippedRatio;
        // Eager fetch here only to fail fast at startup; snapshot() always re-resolves fresh.
        warnIfAnyInvalid(stageMapping.resolveIdsToStages(client.fetchPipelines()));
    }

    private static void warnIfAnyInvalid(StageResolution resolution) {
        if (!resolution.invalidStageReasons().isEmpty()) {
            log.warn("HubSpot pipeline has {} stage(s) that could not be mapped and will be skipped "
                            + "per deal at read time: {}",
                    resolution.invalidStageReasons().size(), resolution.invalidStageReasons());
        }
    }

    @Override
    public String source() {
        return "hubspot";
    }

    @Override
    public DealSnapshot snapshot() {
        StageResolution resolution = stageMapping.resolveIdsToStages(client.fetchPipelines());
        warnIfAnyInvalid(resolution);

        // Single clock read - every Deal below shares this same asOf.
        Instant asOf = clock.instant();

        List<Deal> deals = new ArrayList<>();
        int skippedAtRead = 0;
        int totalRecordsRead = 0;
        for (HubSpotDealRecord record : client.fetchAllDeals()) {
            totalRecordsRead++;
            // Broad catch deliberate: isolates one malformed record without a matching catch per new field.
            try {
                deals.addAll(toDealHistory(record, resolution));
            } catch (RuntimeException e) {
                log.warn("Skipping HubSpot deal {} - failed to parse: {}", record.id(), e.getMessage());
                skippedAtRead++;
            }
        }
        failIfTooManySkipped(skippedAtRead, totalRecordsRead);
        deals.sort(Comparator.comparing(Deal::stageChangedAt).thenComparing(Deal::dealId));
        List<Deal> allDeals = Collections.unmodifiableList(deals);

        Map<String, List<Deal>> history = new LinkedHashMap<>();
        for (Deal deal : allDeals) {
            history.computeIfAbsent(deal.dealId(), id -> new ArrayList<>()).add(deal);
        }
        Map<String, List<Deal>> unmodifiableHistory = new LinkedHashMap<>();
        for (Map.Entry<String, List<Deal>> entry : history.entrySet()) {
            unmodifiableHistory.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }

        List<String> dealIds = Collections.unmodifiableList(new ArrayList<>(unmodifiableHistory.keySet()));

        return new DealSnapshot(
                asOf, allDeals, Collections.unmodifiableMap(unmodifiableHistory), dealIds, skippedAtRead);
    }

    // Exceeding max-skipped-ratio fails the whole run instead of publishing a partial snapshot.
    private void failIfTooManySkipped(int skippedAtRead, int totalRecordsRead) {
        if (totalRecordsRead == 0) {
            return;
        }
        double ratio = (double) skippedAtRead / totalRecordsRead;
        if (ratio > maxSkippedRatio) {
            throw new HubSpotSkipThresholdExceededException(
                    "HubSpot sync skipped " + skippedAtRead + "/" + totalRecordsRead + " deals (ratio "
                            + ratio + "), exceeding bridge.crm.hubspot.max-skipped-ratio=" + maxSkippedRatio
                            + " - failing the whole run instead of publishing a partial snapshot");
        }
    }

    @Override
    public Optional<Deal> fetchDeal(String dealId) {
        if (dealId == null || dealId.isBlank()) {
            throw new IllegalArgumentException("dealId must not be blank");
        }

        Optional<HubSpotDealRecord> record = client.fetchDeal(dealId);
        if (record.isEmpty()) {
            return Optional.empty();
        }

        StageResolution resolution = stageMapping.resolveIdsToStages(client.fetchPipelines());
        warnIfAnyInvalid(resolution);
        List<Deal> history = toDealHistory(record.get(), resolution);
        return Optional.of(history.get(history.size() - 1));
    }

    private List<Deal> toDealHistory(HubSpotDealRecord record, StageResolution resolution) {
        Map<String, List<HubSpotPropertyHistoryEntry>> historyByProperty = record.propertiesWithHistory();
        List<HubSpotPropertyHistoryEntry> newestFirst =
                historyByProperty == null ? null : historyByProperty.get("dealstage");
        if (newestFirst == null || newestFirst.isEmpty()) {
            throw new IllegalStateException(
                    "HubSpot deal " + record.id() + " has no dealstage history in the response");
        }

        List<HubSpotPropertyHistoryEntry> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);

        Map<String, String> properties = record.properties() == null ? Map.of() : record.properties();
        String title = properties.get("dealname");
        LocalDate closeDate = parseDate(properties.get("closedate"));
        BigDecimal dealValue = parseAmount(properties.get("amount"));

        List<Deal> deals = new ArrayList<>(oldestFirst.size());
        for (HubSpotPropertyHistoryEntry entry : oldestFirst) {
            DealStage stage = resolution.idToStage().get(entry.value());
            if (stage == null) {
                String invalidReason = resolution.invalidStageReasons().get(entry.value());
                String detail = invalidReason != null
                        ? "which failed to map: " + invalidReason
                        : "which is not present in the fetched pipeline definition";
                throw new UnknownHubSpotStageException(
                        "HubSpot deal " + record.id() + " references dealstage id \"" + entry.value()
                                + "\", " + detail);
            }
            deals.add(Deal.builder()
                    .dealId(record.id())
                    .sourceRefPrefix(SOURCE_REF_PREFIX)
                    .title(title)
                    .stage(stage)
                    .closeDate(closeDate)
                    .dealValue(dealValue)
                    .createdAt(record.createdAt())
                    .stageChangedAt(entry.timestamp())
                    .build());
        }
        return deals;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return new BigDecimal(value);
    }
}
