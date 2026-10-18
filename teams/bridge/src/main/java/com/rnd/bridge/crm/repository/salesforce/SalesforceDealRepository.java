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
 * Reads the CRM deal feed from the live Salesforce REST API (SOQL against {@code Opportunity}/
 * {@code OpportunityStage}). {@code stageChangedAt} reads the configured field, falling back to
 * {@code CreatedDate} when null (never {@code LastModifiedDate}) - see {@code crm-salesforce-design.md}.
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
            // TODO(load-history): full OpportunityHistory read not implemented yet - see crm-salesforce-design.md §2.
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

    @Override
    public DealSnapshot snapshot() {
        Map<String, DealStage> stageByLabel = stageMapping.resolveLabelsToStages(client.query(STAGE_METADATA_SOQL));

        // Single clock read - every Deal below shares this same asOf.
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
            // Never changed stage -> falls back to CreatedDate, never LastModifiedDate (bumps on any edit).
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
                .currency(null) // not queried - see design doc §3
                .crmAccountSlug(null) // unused downstream - see design doc §3
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
