package com.rnd.bridge.crm.repository;

import com.rnd.bridge.crm.model.Deal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Seeds {@code crm_deals} from the same synthetic JSON dataset
 * {@link JsonDealRepository} reads, so the JDBC path has data without a live
 * CRM feed. Runs only when the table is empty: never truncates, never
 * re-inserts on restart.
 */
@Component
@Profile("jdbc")
public class CrmDealsSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CrmDealsSeeder.class);

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM crm_deals";

    private static final String INSERT_SQL = """
            INSERT INTO crm_deals
                (deal_id, title, description, stage, owner_email, owner_name,
                 close_date, deal_value, currency, crm_account_slug, source_url,
                 created_at, stage_changed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ResourceLoader resourceLoader;
    private final String datasetLocation;

    public CrmDealsSeeder(
            JdbcTemplate jdbcTemplate,
            ResourceLoader resourceLoader,
            @Value("${bridge.crm.dataset:classpath:crm/synthetic-deals.json}") String datasetLocation) {
        this.jdbcTemplate = jdbcTemplate;
        this.resourceLoader = resourceLoader;
        this.datasetLocation = datasetLocation;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer existing = jdbcTemplate.queryForObject(COUNT_SQL, Integer.class);
        if (existing != null && existing > 0) {
            log.info("crm_deals already has {} row(s); skipping seed", existing);
            return;
        }

        List<Deal> deals = SyntheticDealsLoader.load(resourceLoader, datasetLocation);

        List<Object[]> batchArgs = deals.stream()
                .map(deal -> new Object[] {
                        deal.dealId(),
                        deal.title(),
                        deal.description(),
                        deal.stage().name(),
                        deal.ownerEmail(),
                        deal.ownerName(),
                        deal.closeDate(),
                        deal.dealValue(),
                        deal.currency(),
                        deal.crmAccountSlug(),
                        deal.sourceUrl(),
                        toOffsetDateTime(deal.createdAt()),
                        toOffsetDateTime(deal.stageChangedAt())
                })
                .toList();

        jdbcTemplate.batchUpdate(INSERT_SQL, batchArgs);

        log.info("seeded {} row(s) into crm_deals from {}", deals.size(), datasetLocation);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
