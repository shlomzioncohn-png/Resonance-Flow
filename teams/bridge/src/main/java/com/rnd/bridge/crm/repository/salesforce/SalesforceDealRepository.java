package com.rnd.bridge.crm.repository.salesforce;

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
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the CRM deal feed from the live Salesforce REST API (SOQL against {@code Opportunity} and
 * {@code OpportunityStage}), instead of the synthetic JSON file ({@code JsonDealRepository}) or
 * HubSpot ({@code HubSpotDealRepository}). See {@code teams/bridge/docs/crm-salesforce-design.md}
 * for the full rationale this class implements.
 * <p>
 * <b>Current state only, by default - one {@code Deal} per Opportunity.</b> Unlike HubSpot, which
 * tracks per-property history, Salesforce's default read here is just the Opportunity's current
 * row - {@link #snapshot()} and {@link #fetchDeal(String)} both produce exactly one history entry
 * per deal id. This does not violate {@link DealRepository}'s append-only-history contract, it
 * just means the history is always exactly one row long (design doc §2).
 * <p>
 * <b>{@code dealId} is always the bare {@code Opportunity.Id}.</b> {@link Deal#dealId()} never
 * carries a source-specific prefix - {@link #fetchDeal(String)}'s own parameter (what a future CDC
 * event's {@code getRecordId()} would carry, design doc §7, and what every
 * {@code WHERE Id = '...'} SOQL clause needs) and the {@code dealId()} on every {@link Deal} this
 * class returns are the same bare id, so {@code fetchDeal(deal.dealId())} always round-trips. The
 * design doc requires Salesforce's {@code sourceRef} to read {@code "crm:salesforce:<Id>"},
 * distinct from HubSpot's {@code "crm:<id>"} (§4) - that comes from {@link Deal#sourceRefPrefix()}
 * instead: this class sets it to {@code "crm:salesforce:"} (see {@link #SOURCE_REF_PREFIX}), and
 * {@code DealToTaskMapper.toSourceRef} (shared with every other CRM source) builds {@code
 * sourceRef} as {@code deal.sourceRefPrefix() + deal.dealId()} - no per-source branch there, and
 * HubSpot/JSON are unaffected since {@link Deal}'s default prefix is {@code "crm:"}.
 * <p>
 * <b>{@code stageChangedAt} - one configurable field, with a per-row {@code CreatedDate}
 * fallback.</b> Read from whichever Opportunity field
 * {@code bridge.crm.salesforce.stage-changed-at-field} names (default
 * {@code LastStageChangeDate}) - verified 2026-10-14 against a real Developer Edition org (API
 * v68.0): the field exists and is populated once an Opportunity's stage has changed, but is
 * {@code null} for one that never has. {@link #toDeal} falls back to that row's
 * {@code CreatedDate} when the configured field is null/blank - a deal that has never changed
 * stage has been in its current stage since it was created - never {@code LastModifiedDate},
 * which bumps on any field edit and would wrongly reset stalled-deal detection for a deal that
 * was edited but never moved. If {@code CreatedDate} is also missing, the row is skipped and
 * counted in {@link DealSnapshot#skippedAtRead()}, same as any other unparseable record - this
 * is the normal case for a brand-new deal, so it gets no per-record WARN of its own. A deployer
 * who finds the configured field itself absent from the org's schema can still switch
 * {@code bridge.crm.salesforce.stage-changed-at-field} to {@code LastModifiedDate} - this class
 * does not try one field, fail, and silently retry the other at runtime. Design doc §2 has the
 * full verification writeup.
 * <p>
 * <b>Stage mapping.</b> {@link #snapshot()} and {@link #fetchDeal(String)} both resolve
 * {@code OpportunityStage} metadata fresh, on every call, via {@link SalesforceStageMapping} - see
 * that class's Javadoc for why there is no construction-time validation of the fetched metadata
 * the way {@code HubSpotDealRepository} has for its pipeline. A {@code StageName} absent from the
 * resolved metadata is a per-record skip in {@link #snapshot()} (counted in
 * {@link DealSnapshot#skippedAtRead()}), and a thrown {@link IllegalStateException} in
 * {@link #fetchDeal(String)} - never a whole-sync failure in either case.
 * <p>
 * <b>{@code load-history: true} is not supported yet.</b> See the constructor - enabling it fails
 * fast at startup with a clear message rather than silently behaving like current-state-only mode.
 * <p>
 * <b>Freshness.</b> No cache field anywhere on this class - every {@link #snapshot()} and
 * {@link #fetchDeal(String)} call is a fresh, independent set of SOQL queries, mirroring
 * {@code HubSpotDealRepository}'s same rule (CRM-lane audit finding F1).
 */
@Repository
@Profile("salesforce")
public class SalesforceDealRepository implements DealRepository {

    private static final Logger log = LoggerFactory.getLogger(SalesforceDealRepository.class);

    private static final String SOURCE_REF_PREFIX = "crm:salesforce:";

    private static final String STAGE_METADATA_SOQL =
            "SELECT MasterLabel, ApiName, IsActive, IsClosed, IsWon, DefaultProbability FROM OpportunityStage";

    /** {@code HH:mm:ss.SSS} followed by a numeric offset like {@code +0000} - Salesforce's own
     * datetime format, which {@link DateTimeFormatter#ISO_OFFSET_DATE_TIME} cannot parse because
     * it requires a colon in the offset ({@code +00:00}). */
    private static final DateTimeFormatter SALESFORCE_DATETIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
            .appendOffset("+HHMM", "Z")
            .toFormatter();

    private final SalesforceClient client;
    private final Clock clock;
    private final String stageChangedAtField;
    private final SalesforceStageMapping stageMapping;

    @Autowired
    public SalesforceDealRepository(
            RestClient.Builder restClientBuilder,
            @Value("${bridge.crm.salesforce.login-url}") String loginUrl,
            @Value("${bridge.crm.salesforce.client-id}") String clientId,
            @Value("${bridge.crm.salesforce.client-secret}") String clientSecret,
            @Value("${bridge.crm.salesforce.api-version:68.0}") String apiVersion,
            @Value("${bridge.crm.salesforce.max-retries:5}") int maxRetries,
            @Value("${bridge.crm.salesforce.initial-backoff-millis:500}") long initialBackoffMillis,
            @Value("${bridge.crm.salesforce.max-backoff-millis:8000}") long maxBackoffMillis,
            @Value("${bridge.crm.salesforce.connect-timeout-millis:5000}") long connectTimeoutMillis,
            @Value("${bridge.crm.salesforce.read-timeout-millis:15000}") long readTimeoutMillis,
            @Value("${bridge.crm.salesforce.stage-changed-at-field:LastStageChangeDate}") String stageChangedAtField,
            @Value("${bridge.crm.salesforce.load-history:false}") boolean loadHistory,
            @Value("${bridge.crm.salesforce.stage-bands.prospect-max:20}") int prospectMax,
            @Value("${bridge.crm.salesforce.stage-bands.qualified-max:50}") int qualifiedMax,
            @Value("${bridge.crm.salesforce.stage-bands.proposal-max:80}") int proposalMax,
            @Value("${bridge.crm.salesforce.stage-bands.negotiation-max:99}") int negotiationMax) {
        this(SalesforceClient.production(restClientBuilder, loginUrl, clientId, clientSecret, apiVersion,
                        maxRetries, initialBackoffMillis, maxBackoffMillis, connectTimeoutMillis, readTimeoutMillis),
                Clock.systemUTC(), stageChangedAtField, loadHistory,
                new SalesforceStageMapping(prospectMax, qualifiedMax, proposalMax, negotiationMax));
    }

    SalesforceDealRepository(
            SalesforceClient client,
            Clock clock,
            String stageChangedAtField,
            boolean loadHistory,
            SalesforceStageMapping stageMapping) {
        if (loadHistory) {
            // TODO(load-history): read OpportunityHistory (full scan, grouped client-side by
            // OpportunityId, oldest first by CreatedDate - design doc §2) instead of current-state
            // -only Opportunity rows, and resolve each row's StageName label against the
            // OpportunityStage metadata fetched that same call - a label with no current match
            // (an admin renamed/removed the stage since that history row was written) is a
            // per-record skip, not a failure. Not implemented yet - deliberately scoped out of
            // this step rather than built partially; see crm-salesforce-design.md §2/§8.
            throw new IllegalStateException(
                    "bridge.crm.salesforce.load-history=true is not supported yet - leave it false/unset. "
                            + "See SalesforceDealRepository's constructor for what full-history mode would "
                            + "still need to implement.");
        }
        this.client = client;
        this.clock = clock;
        this.stageChangedAtField = (stageChangedAtField == null || stageChangedAtField.isBlank())
                ? "LastStageChangeDate" : stageChangedAtField;
        this.stageMapping = stageMapping;
    }

    @Override
    public String source() {
        return "salesforce";
    }

    /**
     * One fresh fetch - stage metadata and Opportunities both - every call. Never reuses a
     * previous call's data; see the class Javadoc's "Freshness" section.
     */
    @Override
    public DealSnapshot snapshot() {
        Map<String, DealStage> stageByLabel = stageMapping.resolveLabelsToStages(client.query(STAGE_METADATA_SOQL));

        // The one clock read for this fetch. Every Deal built below shares this instant as the
        // snapshot's asOf(), regardless of how many Opportunities are behind it.
        Instant asOf = clock.instant();
        String instanceUrl = client.instanceUrl();

        List<Deal> deals = new ArrayList<>();
        int skippedAtRead = 0;
        for (Map<String, Object> row : client.query(opportunitySoql(null))) {
            try {
                deals.add(toDeal(row, stageByLabel, instanceUrl));
            } catch (RuntimeException e) {
                log.warn("Skipping Salesforce Opportunity {} - failed to parse: {}", row.get("Id"), e.getMessage());
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

    /**
     * One fresh fetch - the single Opportunity, then stage metadata, both real HTTP round trips
     * every call. The stage-metadata fetch only happens when the Opportunity itself was found, so
     * a nonexistent id costs one request, not two - same ordering {@code HubSpotDealRepository}
     * uses for its 404 case, adapted to Salesforce's "not found is zero records" (design doc §2).
     *
     * @param dealId the bare Salesforce {@code Opportunity.Id} - the same value {@link
     *               Deal#dealId()} returns, so {@code fetchDeal(deal.dealId())} round-trips; see
     *               the class Javadoc
     */
    @Override
    public Optional<Deal> fetchDeal(String dealId) {
        if (dealId == null || dealId.isBlank()) {
            throw new IllegalArgumentException("dealId must not be blank");
        }

        List<Map<String, Object>> records = client.query(opportunitySoql(dealId));
        if (records.isEmpty()) {
            return Optional.empty();
        }

        Map<String, DealStage> stageByLabel = stageMapping.resolveLabelsToStages(client.query(STAGE_METADATA_SOQL));
        String instanceUrl = client.instanceUrl();
        return Optional.of(toDeal(records.get(0), stageByLabel, instanceUrl));
    }

    private Deal toDeal(Map<String, Object> row, Map<String, DealStage> stageByLabel, String instanceUrl) {
        String rawId = (String) row.get("Id");
        String stageName = (String) row.get("StageName");
        DealStage stage = stageByLabel.get(stageName);
        if (stage == null) {
            throw new IllegalStateException(
                    "Salesforce Opportunity " + rawId + " references StageName \"" + stageName
                            + "\", which is not present in the fetched OpportunityStage metadata");
        }

        Map<String, Object> owner = asMap(row.get("Owner"));
        Instant createdAt = parseInstant((String) row.get("CreatedDate"));
        Instant stageChangedAt = parseInstant((String) row.get(stageChangedAtField));
        if (stageChangedAt == null) {
            // A deal that has never changed stage has been in its current stage since it was
            // created - CreatedDate, never LastModifiedDate, which bumps on any field edit.
            stageChangedAt = createdAt;
        }

        return Deal.builder()
                .dealId(rawId)
                .sourceRefPrefix(SOURCE_REF_PREFIX)
                .title((String) row.get("Name"))
                .description((String) row.get("Description"))
                .stage(stage)
                .ownerName(owner == null ? null : (String) owner.get("Name"))
                .ownerEmail(owner == null ? null : (String) owner.get("Email"))
                .closeDate(parseLocalDate((String) row.get("CloseDate")))
                .dealValue(toBigDecimal(row.get("Amount")))
                .currency(null) // CurrencyIsoCode is never queried - see class Javadoc / design doc §3
                .crmAccountSlug(null) // nothing downstream reads it - see design doc §3
                .sourceUrl(instanceUrl + "/" + rawId)
                .createdAt(createdAt)
                .stageChangedAt(stageChangedAt)
                .build();
    }

    private String opportunitySoql(String dealId) {
        String base = "SELECT Id, Name, Description, StageName, Amount, CloseDate, CreatedDate, "
                + stageChangedAtField + ", Owner.Name, Owner.Email FROM Opportunity";
        return dealId == null ? base + " ORDER BY Id" : base + " WHERE Id = '" + escapeSoql(dealId) + "'";
    }

    private static String escapeSoql(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static LocalDate parseLocalDate(String value) {
        return (value == null || value.isBlank()) ? null : LocalDate.parse(value);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(value, SALESFORCE_DATETIME).toInstant();
    }

    private static BigDecimal toBigDecimal(Object value) {
        return value instanceof Number number ? new BigDecimal(number.toString()) : null;
    }
}
