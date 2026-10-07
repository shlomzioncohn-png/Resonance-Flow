package com.rnd.bridge.crm.repository.hubspot;

import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.crm.repository.DealRepository;
import com.rnd.bridge.crm.repository.DealSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
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

/**
 * Reads the CRM deal feed straight from the live HubSpot API, instead of the
 * synthetic JSON file ({@code JsonDealRepository}).
 * <p>
 * <b>The shape mismatch.</b> {@link DealRepository} wants append-only
 * stage-change events, oldest first. HubSpot's deals endpoint returns
 * current state plus, when {@code propertiesWithHistory=dealstage} is
 * requested, that one property's history newest-first. This repository
 * bridges the two by reversing the history array and emitting one
 * {@link Deal} per entry, carrying that entry's stage and timestamp - every
 * other field ({@code title}, {@code closeDate}, {@code dealValue}, ...) is
 * copied from the deal's <em>current</em> state onto every historical
 * record, because HubSpot only tracks history for the property actually
 * requested. A deal whose amount changed shows today's amount on all of its
 * historical rows. Requesting history for every property to avoid this
 * would mean merging several independently-timestamped histories for fields
 * nothing downstream reads -
 * {@link com.rnd.bridge.crm.mapper.DealToTaskMapper} only reads {@code
 * stage}, {@code closeDate}, {@code title} and {@code dealId} - so that
 * complexity is deliberately not taken on.
 * <p>
 * <b>Fields with no direct HubSpot source</b> - {@code description},
 * {@code ownerEmail}, {@code ownerName}, {@code currency},
 * {@code crmAccountSlug}, {@code sourceUrl} - are left {@code null} rather
 * than guessed at. Populating them would mean extra lookups (owners,
 * portal id for a deal URL, a currency property) that nothing in this
 * program's mapping path needs.
 * <p>
 * <b>Stage mapping.</b> HubSpot stage ids are opaque and portal-specific, so they are never
 * hardcoded. {@link #snapshot()} fetches the pipeline definition and resolves it fresh on every
 * call - see {@link HubSpotStageMapping} for the table and the rationale for its one many-to-one
 * collapse. The constructor ALSO resolves it once, eagerly, discarding the result, purely to fail
 * fast at startup if this portal's pipeline has a stage label {@link HubSpotStageMapping} doesn't
 * recognize - a known tradeoff ({@code CLAUDE.md}): the first {@link #snapshot()} call after
 * startup re-does that same fetch. {@link DealStage#ON_HOLD} has no HubSpot equivalent and this
 * repository never produces it.
 * <p>
 * <b>Two different failure shapes for "unrecognized stage," by design, not by accident.</b> A deal
 * whose HISTORY references a stage ID absent from an otherwise-valid, fully-resolved pipeline
 * mapping is a per-record skip - counted in {@link DealSnapshot#skippedAtRead()}, the rest of the
 * fetch proceeds. A pipeline whose STAGE LABEL itself isn't in {@link HubSpotStageMapping}'s table
 * fails {@link #snapshot()} entirely, before the deals endpoint is ever called - see
 * {@link HubSpotStageMapping#resolveIdsToStages}.
 * <p>
 * <b>Freshness.</b> {@link #snapshot()} performs one fresh, fully independent fetch - pipeline and
 * deals both - every time it is called, and returns an immutable {@link DealSnapshot} whose {@code
 * asOf()} and every deal in it come from that one fetch. There is no cache field anywhere on this
 * class; every call is a real HTTP round trip. This is deliberate (CRM-lane audit finding F1): a
 * long-lived singleton bean that cached one fetch for its whole lifetime meant every sync after the
 * first silently republished whatever the first fetch saw, until the pod restarted.
 * <p>
 * The token is read once, from configuration backed by the {@code
 * HUBSPOT_TOKEN} environment variable, purely to build the {@code
 * Authorization} header - it is never hardcoded, logged, or included in an
 * error message (see {@link HubSpotClientException}).
 */
@Repository
@Profile("hubspot")
@Primary
public class HubSpotDealRepository implements DealRepository {

    private static final Logger log = LoggerFactory.getLogger(HubSpotDealRepository.class);

    private final HubSpotClient client;
    private final Clock clock;

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
            @Value("${bridge.crm.hubspot.read-timeout-millis:15000}") long readTimeoutMillis) {
        this(HubSpotClient.production(restClientBuilder, baseUrl, token, pageSize, maxRetries,
                initialBackoffMillis, maxBackoffMillis, connectTimeoutMillis, readTimeoutMillis),
                Clock.systemUTC());
    }

    HubSpotDealRepository(HubSpotClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
        // Fail fast at startup if this portal's pipeline has a stage label HubSpotStageMapping
        // doesn't recognize. The result itself is discarded - snapshot() always resolves its own
        // fresh copy below, never reusing this one (see the class Javadoc's "Stage mapping" note).
        HubSpotStageMapping.resolveIdsToStages(client.fetchPipelines());
    }

    /**
     * One fresh fetch - pipeline and deals both - every call. Never reuses a previous call's data,
     * so a sync made an hour (or a stage change) after the last one reflects HubSpot's current
     * state. See the class Javadoc's "Freshness" section.
     */
    @Override
    public DealSnapshot snapshot() {
        Map<String, DealStage> stageMapping = HubSpotStageMapping.resolveIdsToStages(client.fetchPipelines());

        // The one clock read for this fetch. Every Deal built below shares this instant as the
        // snapshot's asOf(), regardless of how many pages or deals are behind it.
        Instant asOf = clock.instant();

        List<Deal> deals = new ArrayList<>();
        int skippedAtRead = 0;
        for (HubSpotDealRecord record : client.fetchAllDeals()) {
            // toDealHistory() parses this one record's date/amount/stage
            // history - each of those is a claim about HubSpot's response
            // shape, not something this lane controls. Catching broadly and
            // by record, not by exception type, is deliberate: any parsing
            // rule added to toDealHistory() later - a new field, a new
            // format assumption - gets this same per-record safety net
            // without a matching catch clause added here.
            try {
                deals.addAll(toDealHistory(record, stageMapping));
            } catch (RuntimeException e) {
                log.warn("Skipping HubSpot deal {} - failed to parse: {}", record.id(), e.getMessage());
                skippedAtRead++;
            }
        }
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

    private List<Deal> toDealHistory(HubSpotDealRecord record, Map<String, DealStage> stageMapping) {
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
            DealStage stage = stageMapping.get(entry.value());
            if (stage == null) {
                throw new UnknownHubSpotStageException(
                        "HubSpot deal " + record.id() + " references dealstage id \"" + entry.value()
                                + "\", which is not present in the fetched pipeline definition");
            }
            deals.add(Deal.builder()
                    .dealId(record.id())
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
