package com.rnd.bridge.crm.repository.hubspot;

import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.crm.repository.DealRepository;
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
import java.util.Optional;

/**
 * Reads the CRM deal feed straight from the live HubSpot API, instead of the
 * synthetic JSON file or the Postgres staging table.
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
 * <b>Stage mapping.</b> HubSpot stage ids are opaque and portal-specific, so
 * they are never hardcoded: the pipeline definition is fetched once at
 * startup ({@code GET /crm/v3/pipelines/deals}) and each stage's label is
 * matched against HubSpot's known default-pipeline labels to build an
 * id-to-{@link DealStage} table for this portal - see
 * {@link HubSpotStageMapping} for the table and the rationale for its one
 * many-to-one collapse. A pipeline stage whose label isn't recognised fails
 * application startup ({@link UnknownHubSpotStageException}); a deal whose
 * history somehow references a stage id absent from that table fails the
 * sync with the same exception. Neither case is silently dropped or
 * defaulted. {@link DealStage#ON_HOLD} has no HubSpot equivalent and this
 * repository never produces it.
 * <p>
 * <b>{@code asOf()}.</b> {@code JsonDealRepository} reads {@code asOf} from
 * the file envelope; {@code JdbcDealRepository} takes it from configuration.
 * Neither applies here - a live API has no notion of "as of when" built in.
 * The clock is read exactly once, at the start of a sync, in
 * {@link #fetchSync()} - deliberately not buried inside the per-record
 * mapping loop - and every {@link Deal} that sync produces shares the
 * resulting instant. "A sync" here is: the first call to any finder method
 * triggers one full, paginated fetch of every deal, which is then cached
 * for the remaining lifetime of this repository instance, so {@link
 * #asOf()} and every {@code find*} call agree with each other no matter
 * which is called first or how many times. Unlike {@code
 * JdbcDealRepository} - a local, cheap-to-repeat query - re-running a
 * multi-page HTTP fetch on every accessor call would multiply network calls
 * and rate-limit exposure for no benefit within one logical sync. Observing
 * a genuinely new HubSpot state means restarting this repository (a new
 * deployment), not calling {@link #findAll()} again.
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

    private final HubSpotClient client;
    private final Clock clock;
    private final Map<String, DealStage> stageMapping;

    private final Object syncLock = new Object();
    private volatile SyncSnapshot snapshot;

    @Autowired
    public HubSpotDealRepository(
            RestClient.Builder restClientBuilder,
            @Value("${bridge.crm.hubspot.base-url:https://api.hubspot.com}") String baseUrl,
            @Value("${bridge.crm.hubspot.token}") String token,
            @Value("${bridge.crm.hubspot.page-size:100}") int pageSize,
            @Value("${bridge.crm.hubspot.max-retries:5}") int maxRetries,
            @Value("${bridge.crm.hubspot.initial-backoff-millis:500}") long initialBackoffMillis,
            @Value("${bridge.crm.hubspot.max-backoff-millis:8000}") long maxBackoffMillis) {
        this(HubSpotClient.production(restClientBuilder, baseUrl, token, pageSize, maxRetries,
                initialBackoffMillis, maxBackoffMillis), Clock.systemUTC());
    }

    HubSpotDealRepository(HubSpotClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
        // Fetched once, at startup, deliberately outside fetchSync() - the
        // pipeline definition does not change per sync the way deal data does.
        this.stageMapping = HubSpotStageMapping.resolveIdsToStages(client.fetchPipelines());
    }

    @Override
    public Instant asOf() {
        return currentSync().asOf();
    }

    @Override
    public List<Deal> findAll() {
        return currentSync().allDeals();
    }

    @Override
    public List<Deal> findHistoryByDealId(String dealId) {
        return currentSync().historyByDealId().getOrDefault(dealId, List.of());
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
        return currentSync().dealIds();
    }

    private SyncSnapshot currentSync() {
        SyncSnapshot local = snapshot;
        if (local != null) {
            return local;
        }
        synchronized (syncLock) {
            if (snapshot == null) {
                snapshot = fetchSync();
            }
            return snapshot;
        }
    }

    private SyncSnapshot fetchSync() {
        // The one clock read for this sync. Every Deal built below shares
        // this instant as the repository's asOf(), regardless of how many
        // pages or deals are behind it.
        Instant asOf = clock.instant();

        List<Deal> deals = new ArrayList<>();
        for (HubSpotDealRecord record : client.fetchAllDeals()) {
            deals.addAll(toDealHistory(record));
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

        return new SyncSnapshot(asOf, allDeals, Collections.unmodifiableMap(unmodifiableHistory), dealIds);
    }

    private List<Deal> toDealHistory(HubSpotDealRecord record) {
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
                                + "\", which is not present in the pipeline definition fetched at startup");
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

    private record SyncSnapshot(
            Instant asOf,
            List<Deal> allDeals,
            Map<String, List<Deal>> historyByDealId,
            List<String> dealIds) {
    }
}
