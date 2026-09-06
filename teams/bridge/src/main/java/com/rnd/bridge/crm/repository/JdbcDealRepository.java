package com.rnd.bridge.crm.repository;

import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the CRM deal feed from the {@code crm_deals} staging table populated
 * by {@link CrmDealsSeeder}, instead of the synthetic JSON file.
 * <p>
 * Unlike {@link JsonDealRepository}, this does NOT fail fast when there is
 * no data: an empty {@code crm_deals} table is a legitimate state (before
 * the seeder, or a real CRM export job, has run) - not a startup failure.
 * {@code JsonDealRepository}'s fail-fast constructor is right for a fixed
 * resource that is either present and correct or a build error; a staging
 * table starting empty is normal, so that pattern is deliberately not
 * copied here.
 */
@Repository
@Profile("jdbc")
@Primary
public class JdbcDealRepository implements DealRepository {

    private static final String SELECT_ALL =
            "SELECT * FROM crm_deals ORDER BY stage_changed_at ASC, deal_id ASC";

    private static final String SELECT_BY_DEAL_ID =
            "SELECT * FROM crm_deals WHERE deal_id = ? ORDER BY stage_changed_at ASC, deal_id ASC";

    private static final RowMapper<Deal> ROW_MAPPER = (rs, rowNum) -> Deal.builder()
            .dealId(rs.getString("deal_id"))
            .title(rs.getString("title"))
            .description(rs.getString("description"))
            .stage(DealStage.valueOf(rs.getString("stage")))
            .ownerEmail(rs.getString("owner_email"))
            .ownerName(rs.getString("owner_name"))
            .closeDate(rs.getObject("close_date", LocalDate.class))
            .dealValue(rs.getBigDecimal("deal_value"))
            .currency(rs.getString("currency"))
            .crmAccountSlug(rs.getString("crm_account_slug"))
            .sourceUrl(rs.getString("source_url"))
            .createdAt(toInstant(rs.getObject("created_at", OffsetDateTime.class)))
            .stageChangedAt(toInstant(rs.getObject("stage_changed_at", OffsetDateTime.class)))
            .build();

    private final JdbcTemplate jdbcTemplate;
    private final Instant asOf;

    public JdbcDealRepository(JdbcTemplate jdbcTemplate, @Value("${bridge.crm.as-of}") String asOf) {
        this.jdbcTemplate = jdbcTemplate;
        // @Value has no default expression, so a missing bridge.crm.as-of
        // fails Spring's property resolution before this constructor even
        // runs - a loud startup error rather than a silent Instant.now().
        this.asOf = Instant.parse(Objects.requireNonNull(asOf));
    }

    @Override
    public Instant asOf() {
        return asOf;
    }

    @Override
    public List<Deal> findAll() {
        return jdbcTemplate.query(SELECT_ALL, ROW_MAPPER);
    }

    @Override
    public List<Deal> findHistoryByDealId(String dealId) {
        return jdbcTemplate.query(SELECT_BY_DEAL_ID, ROW_MAPPER, dealId);
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
        // distinct() on an ordered stream keeps first-occurrence order, the
        // same "first seen while iterating stage_changed_at-then-deal_id
        // order" semantics JsonDealRepository gets from a LinkedHashMap.
        return findAll().stream()
                .map(Deal::dealId)
                .distinct()
                .toList();
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
