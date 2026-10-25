package com.rnd.chain.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
class TaskReadinessViewIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    

    @Test
    void taskReadinessView_derivesBlockingAndConstraintCounts() {

        UUID tenantId = UUID.randomUUID();

        UUID blockedTaskId = UUID.randomUUID();
        UUID activeBlockerId = UUID.randomUUID();
        UUID doneBlockerId = UUID.randomUUID();

        UUID readyTaskId = UUID.randomUUID();
        UUID doneTaskId = UUID.randomUUID();

        insertTask(doneTaskId, tenantId, "DONE");

        // Task שאמורה להיות חסומה:
        // blocker פעיל אחד + constraint פתוח אחד.
        insertTask(blockedTaskId, tenantId, "ACTIVE");

        insertTask(activeBlockerId, tenantId, "ACTIVE");

        insertTask(doneBlockerId, tenantId, "DONE");

        // dependency פעיל - צריך להיספר
        jdbcTemplate.update("""
                INSERT INTO task_dependency(task_id, depends_on_task_id)
                VALUES (?, ?)
                """,
                blockedTaskId,
                activeBlockerId);

        // dependency ל-DONE - לא צריך להיספר
        jdbcTemplate.update("""
                INSERT INTO task_dependency(task_id, depends_on_task_id)
                VALUES (?, ?)
                """,
                blockedTaskId,
                doneBlockerId);

        // constraint פתוח - צריך להיספר
        jdbcTemplate.update("""
                INSERT INTO task_constraint(
                    constraint_id,
                    task_id,
                    type,
                    status,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, 'APPROVAL_NEEDED', 'PENDING', now(), now())
                """,
                UUID.randomUUID().toString(),
                blockedTaskId);

        // constraint סגור - לא צריך להיספר
        jdbcTemplate.update("""
                INSERT INTO task_constraint(
                    constraint_id,
                    task_id,
                    type,
                    status,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, 'APPROVAL_NEEDED', 'SATISFIED', now(), now())
                """,
                UUID.randomUUID().toString(),
                blockedTaskId);

        // Task מוכנה לחלוטין
        insertTask(readyTaskId, tenantId, "ACTIVE");

        assertReadiness(blockedTaskId, 1, 1, false);
        assertReadiness(readyTaskId, 0, 0, true);
        assertReadiness(doneTaskId, 0, 0, true);
    }

    private void insertTask(UUID id, UUID tenantId, String status) {

        jdbcTemplate.update("""
                INSERT INTO task(
                    id,
                    tenant_id,
                    status
                )
                VALUES (?, ?, ?)
                """,
                id,
                tenantId,
                status);
    }

    private void assertReadiness(UUID taskId, long blockingCount, long openConstraintCount, boolean isReady) {

        jdbcTemplate.queryForObject("""
                SELECT blocking_count, open_constraint_count, is_ready
                FROM task_readiness
                WHERE id = ?
                """,
                (rs, rowNum) -> {

                    assertEquals(blockingCount, rs.getLong("blocking_count"));
                    assertEquals(openConstraintCount, rs.getLong("open_constraint_count"));
                    assertEquals(isReady, rs.getBoolean("is_ready"));

                    return null;
                },
                taskId);
    }
}
