package com.rnd.inbox.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.UUID;

// These tests run against whatever Postgres the application is configured for - on a dev machine
// that is the shared local resonance_flow database, seed data included, not a throwaway
// container. So a test must only ever remove rows it created itself: a repository.deleteAll()
// in a cleanup method empties the whole table for everyone. Create every row under a tenant the
// test persisted, then hand those tenant ids to deleteTenantData.
@SpringBootTest(properties = "inbox.scheduling.enabled=false")
public abstract class PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Removes everything that lives under the given tenants and nothing else. Explicit order
    // instead of relying on the tenants FK cascade alone: teams.team_lead_id is ON DELETE
    // RESTRICT, so teams have to go before their users; outbox and audit_log rows aren't
    // cascaded from tasks/tenants at all and would otherwise be left behind.
    protected void deleteTenantData(Collection<UUID> tenantIds) {
        for (UUID tenantId : tenantIds) {
            jdbcTemplate.update(
                    "DELETE FROM outbox WHERE aggregate_id IN (SELECT id FROM tasks WHERE tenant_id = ?)", tenantId);
            jdbcTemplate.update("DELETE FROM audit_log WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM insights WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM tasks WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM teams WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM projects WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM users WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM tenants WHERE id = ?", tenantId);
        }
    }

    protected int countTasksInTenant(UUID tenantId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tasks WHERE tenant_id = ?", Integer.class, tenantId);
    }

    protected int countTasksWithTitle(String title) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tasks WHERE title = ?", Integer.class, title);
    }
}
