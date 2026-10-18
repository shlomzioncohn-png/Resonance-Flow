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
    void taskReadinessView_matchesStoredReadinessColumns() {

        UUID tenantId = UUID.randomUUID();

        UUID blockedTaskId = UUID.randomUUID();
        UUID activeBlockerId = UUID.randomUUID();
        UUID doneBlockerId = UUID.randomUUID();

        UUID readyTaskId = UUID.randomUUID();

        // Task שאמורה להיות חסומה:
        // blocker פעיל אחד + constraint פתוח אחד.
        insertTask(
                blockedTaskId,
                tenantId,
                "ACTIVE",
                false,
                1,
                1);

        insertTask(
                activeBlockerId,
                tenantId,
                "ACTIVE",
                true,
                0,
                0);

        insertTask(
                doneBlockerId,
                tenantId,
                "DONE",
                true,
                0,
                0);

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
        insertTask(
                readyTaskId,
                tenantId,
                "ACTIVE",
                true,
                0,
                0);

        assertViewMatchesStoredColumns(blockedTaskId);
        assertViewMatchesStoredColumns(readyTaskId);
    }

    private void insertTask(
            UUID id,
            UUID tenantId,
            String status,
            boolean isReady,
            int blockingCount,
            int openConstraintCount) {

        jdbcTemplate.update("""
                INSERT INTO task(
                    id,
                    tenant_id,
                    status,
                    is_ready,
                    blocking_count,
                    open_constraint_count
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id,
                tenantId,
                status,
                isReady,
                blockingCount,
                openConstraintCount);
    }

    private void assertViewMatchesStoredColumns(UUID taskId) {

        jdbcTemplate.queryForObject("""
                SELECT
                    t.blocking_count AS stored_blocking_count,
                    tr.blocking_count AS calculated_blocking_count,

                    t.open_constraint_count AS stored_open_constraint_count,
                    tr.open_constraint_count AS calculated_open_constraint_count,

                    t.is_ready AS stored_is_ready,
                    tr.is_ready AS calculated_is_ready

                FROM task t
                JOIN task_readiness tr ON tr.id = t.id
                WHERE t.id = ?
                """,
                (rs, rowNum) -> {

                    assertEquals(
                            rs.getLong("stored_blocking_count"),
                            rs.getLong("calculated_blocking_count"));

                    assertEquals(
                            rs.getLong("stored_open_constraint_count"),
                            rs.getLong("calculated_open_constraint_count"));

                    assertEquals(
                            rs.getBoolean("stored_is_ready"),
                            rs.getBoolean("calculated_is_ready"));

                    return null;
                },
                taskId);
    }
}