CREATE VIEW task_readiness AS
SELECT
    t.id,
    t.tenant_id,

    (
        SELECT COUNT(*)
        FROM task_dependency td
        JOIN task blocker
            ON blocker.id = td.depends_on_task_id
        WHERE td.task_id = t.id
          AND blocker.status <> 'DONE'
          AND blocker.deleted_at IS NULL
    ) AS blocking_count,

    (
        SELECT COUNT(*)
        FROM task_constraint tc
        WHERE tc.task_id = t.id
          AND tc.status = 'PENDING'
    ) AS open_constraint_count,

    (
        NOT EXISTS (
            SELECT 1
            FROM task_dependency td
            JOIN task blocker
                ON blocker.id = td.depends_on_task_id
            WHERE td.task_id = t.id
              AND blocker.status <> 'DONE'
              AND blocker.deleted_at IS NULL
        )
        AND NOT EXISTS (
            SELECT 1
            FROM task_constraint tc
            WHERE tc.task_id = t.id
              AND tc.status = 'PENDING'
        )
    ) AS is_ready

FROM task t
WHERE t.deleted_at IS NULL
  AND t.status <> 'DONE';


CREATE INDEX idx_task_constraint_pending
    ON task_constraint(task_id)
    WHERE status = 'PENDING';


CREATE INDEX idx_task_active_not_done
    ON task(id)
    WHERE deleted_at IS NULL
      AND status <> 'DONE';