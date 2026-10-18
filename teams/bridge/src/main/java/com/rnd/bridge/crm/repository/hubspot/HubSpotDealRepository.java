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
import java.util.Optional;

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
 * call, by stage <em>metadata</em> ({@code isClosed}/{@code probability}), never by label - see
 * {@link HubSpotStageMapping} for the mapping rules (the actual fix for F6: a renamed or custom
 * stage label no longer breaks anything). The constructor ALSO resolves it once, eagerly,
 * discarding the result, purely to fail fast at startup on a pipeline/stage-level problem severe
 * enough that no individual deal could be isolated from it - a known tradeoff ({@code CLAUDE.md}):
 * the first {@link #snapshot()} call after startup re-does that same fetch. {@link
 * DealStage#ON_HOLD} has no HubSpot equivalent and this repository never produces it.
 * <p>
 * <b>Two different failure shapes for "a stage can't be mapped," by design, not by accident.</b> A
 * deal whose HISTORY references a stage id {@link HubSpotStageMapping} marked invalid (or that
 * isn't present in the pipeline at all) is a per-record skip - counted in {@link
 * DealSnapshot#skippedAtRead()}, the rest of the fetch proceeds; the constructor and every {@link
 * #snapshot()} call also log one WARN per run listing every invalid stage id and why, independent
 * of whether any deal currently references it. A pipeline/stage-level problem {@link
 * HubSpotStageMapping#resolveIdsToStages} can't isolate to one stage - zero stages fetched, a
 * stage with no id, or every fetched stage turning out invalid - fails {@link #snapshot()}
 * entirely, before the deals endpoint is ever called. On top of both: if the fraction of deals
 * skipped this way in one {@link #snapshot()} call exceeds {@code
 * bridge.crm.hubspot.max-skipped-ratio} (one bad stage used by many deals), the whole run fails
 * with {@link HubSpotSkipThresholdExceededException} instead of publishing a mostly-skipped
 * snapshot as if it were a normal sync.
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
        // Fail fast at startup on a pipeline/stage-level problem resolveIdsToStages can't isolate
        // to one stage (see its Javadoc). A per-stage problem it CAN isolate never throws here -
        // only logged, below - so the app still starts even with one malformed stage in the
        // portal. The result itself is discarded - snapshot() always resolves its own fresh copy
        // (see the class Javadoc's "Stage mapping" note).
        warnIfAnyInvalid(stageMapping.resolveIdsToStages(client.fetchPipelines()));
    }

    private static void warnIfAnyInvalid(StageResolution resolution) {
        if (!resolution.invalidStageReasons().isEmpty()) {
            log.warn("HubSpot pipeline has {} stage(s) that could not be mapped and will be skipped "
                            + "per deal at read time: {}",
                    resolution.invalidStageReasons().size(), resolution.invalidStageReasons());
        }
    }

    /**
     * One fresh fetch - pipeline and deals both - every call. Never reuses a previous call's data,
     * so a sync made an hour (or a stage change) after the last one reflects HubSpot's current
     * state. See the class Javadoc's "Freshness" section.
     */
    @Override
    public DealSnapshot snapshot() {
        StageResolution resolution = stageMapping.resolveIdsToStages(client.fetchPipelines());
        warnIfAnyInvalid(resolution);

        // The one clock read for this fetch. Every Deal built below shares this instant as the
        // snapshot's asOf(), regardless of how many pages or deals are behind it.
        Instant asOf = clock.instant();

        List<Deal> deals = new ArrayList<>();
        int skippedAtRead = 0;
        int totalRecordsRead = 0;
        for (HubSpotDealRecord record : client.fetchAllDeals()) {
            totalRecordsRead++;
            // toDealHistory() parses this one record's date/amount/stage
            // history - each of those is a claim about HubSpot's response
            // shape, not something this lane controls. Catching broadly and
            // by record, not by exception type, is deliberate: any parsing
            // rule added to toDealHistory() later - a new field, a new
            // format assumption - gets this same per-record safety net
            // without a matching catch clause added here.
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

    /**
     * Fails the whole run instead of returning a partial {@link DealSnapshot} once {@code
     * skippedAtRead} exceeds {@code bridge.crm.hubspot.max-skipped-ratio} of the deals actually
     * read this call - one invalid stage used by many deals makes every one of them fail the same
     * way, and past this ratio a "mostly successful" sync is really a broken one. Zero records
     * read is never a failure - there is nothing to divide by, and nothing was skipped either.
     */
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

    /**
     * One fresh fetch - the single deal, then the pipeline, both real HTTP round trips every call
     * - never reusing a previous {@link #snapshot()} or {@link #fetchDeal(String)} call's data.
     * The pipeline fetch only happens when the deal itself was found, so a 404 costs one request,
     * not two. See the class Javadoc's "Freshness" and "Stage mapping" sections, and {@link
     * DealRepository#fetchDeal(String)}'s own Javadoc for the "latest state" / "no asOf" contract
     * this satisfies.
     */
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
