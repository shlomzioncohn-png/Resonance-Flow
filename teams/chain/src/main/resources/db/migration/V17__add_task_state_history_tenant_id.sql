-- Issue #1000: tenant-level state-history queries. Nullable: history outlives deleted tasks, so rows
-- whose task is gone cannot be backfilled and stay outside every tenant's results.
ALTER TABLE task_state_history ADD COLUMN tenant_id UUID;

UPDATE task_state_history h
SET tenant_id = t.tenant_id
FROM task t
WHERE t.id = h.task_id;

CREATE INDEX idx_task_state_history_tenant_id_created_at
    ON task_state_history(tenant_id, created_at, sequence);
