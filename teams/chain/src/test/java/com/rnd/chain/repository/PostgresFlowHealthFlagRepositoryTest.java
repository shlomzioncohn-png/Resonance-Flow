package com.rnd.chain.repository;

import com.rnd.chain.dto.FlowHealthFlag;
import com.rnd.chain.dto.FlowHealthPattern;
import com.rnd.chain.dto.FlowHealthSeverity;
import com.rnd.chain.dto.PageResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link PostgresFlowHealthFlagRepository} against a real Postgres instance, above all that a tenant
 * only ever sees its own flags. {@code @Tag("postgres")}: excluded from the default {@code mvn test}, run
 * with {@code -Ppostgres-integration}.
 */
@SpringBootTest
@Tag("postgres")
class PostgresFlowHealthFlagRepositoryTest {

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @Autowired
    private FlowHealthFlagRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM flow_health_flag WHERE tenant_id IN (?, ?)", tenantA, tenantB);
    }

    private void insert(UUID tenant, String flagId, String pattern, String severity, int rank) {
        jdbc.update("INSERT INTO flow_health_flag (tenant_id, flag_id, pattern, entity_type, entity_ids, severity, "
                        + "score, rank, explanation, evidence, run_at) "
                        + "VALUES (?, ?, ?, 'task', '[\"t1\"]'::jsonb, ?, 0.5, ?, 'why', '{\"days\": 3}'::jsonb, now())",
                tenant, flagId, pattern, severity, rank);
    }

    @Test
    void findPage_returnsOnlyTheCallersTenantsFlags() {
        insert(tenantA, "CHRONIC_BLOCKER:a1", "CHRONIC_BLOCKER", "high", 1);
        insert(tenantB, "CHRONIC_BLOCKER:b1", "CHRONIC_BLOCKER", "high", 1);
        insert(tenantB, "SLOW_CHAIN:b2", "SLOW_CHAIN", "low", 2);

        PageResponse<FlowHealthFlag> forA = repository.findPage(tenantA, null, null, 0, 20);
        PageResponse<FlowHealthFlag> forB = repository.findPage(tenantB, null, null, 0, 20);

        assertThat(forA.content()).extracting(FlowHealthFlag::flagId).containsExactly("CHRONIC_BLOCKER:a1");
        assertThat(forA.totalElements()).isEqualTo(1);
        assertThat(forB.content()).extracting(FlowHealthFlag::flagId)
                .containsExactly("CHRONIC_BLOCKER:b1", "SLOW_CHAIN:b2");
    }

    @Test
    void findPage_aTenantWithNoFlagsGetsAnEmptyPage() {
        insert(tenantA, "CHRONIC_BLOCKER:a1", "CHRONIC_BLOCKER", "high", 1);

        PageResponse<FlowHealthFlag> page = repository.findPage(UUID.randomUUID(), null, null, 0, 20);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void findPage_filtersStayInsideTheTenant() {
        insert(tenantA, "CHRONIC_BLOCKER:a1", "CHRONIC_BLOCKER", "high", 1);
        insert(tenantA, "SLOW_CHAIN:a2", "SLOW_CHAIN", "low", 2);
        insert(tenantB, "CHRONIC_BLOCKER:b1", "CHRONIC_BLOCKER", "high", 1);

        assertThat(repository.findPage(tenantA, FlowHealthPattern.CHRONIC_BLOCKER, null, 0, 20).content())
                .extracting(FlowHealthFlag::flagId).containsExactly("CHRONIC_BLOCKER:a1");
        assertThat(repository.findPage(tenantA, null, FlowHealthSeverity.LOW, 0, 20).content())
                .extracting(FlowHealthFlag::flagId).containsExactly("SLOW_CHAIN:a2");
    }

    @Test
    void sameFlagIdCanExistInTwoTenants() {
        insert(tenantA, "CHRONIC_BLOCKER:same", "CHRONIC_BLOCKER", "high", 1);
        insert(tenantB, "CHRONIC_BLOCKER:same", "CHRONIC_BLOCKER", "low", 1);

        assertThat(repository.findPage(tenantA, null, null, 0, 20).content().get(0).severity())
                .isEqualTo(FlowHealthSeverity.HIGH);
        assertThat(repository.findPage(tenantB, null, null, 0, 20).content().get(0).severity())
                .isEqualTo(FlowHealthSeverity.LOW);
    }

    @Test
    void findPage_mapsJsonColumns() {
        insert(tenantA, "CHRONIC_BLOCKER:a1", "CHRONIC_BLOCKER", "high", 1);

        FlowHealthFlag flag = repository.findPage(tenantA, null, null, 0, 20).content().get(0);

        assertThat(flag.entityIds()).containsExactly("t1");
        assertThat(flag.evidence().get("days").asInt()).isEqualTo(3);
    }
}
