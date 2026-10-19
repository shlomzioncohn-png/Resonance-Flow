-- Issue #773: append-only history of task status changes and blocking transitions, for
-- Dependency-Flow Health Analytics. Written in the same transaction as the change itself.
CREATE TABLE task_state_history (
    id                    UUID PRIMARY KEY,
    task_id               UUID NOT NULL,
    event_type            VARCHAR(20) NOT NULL,
    from_status           VARCHAR(20),
    to_status             VARCHAR(20),
    blocking_count_before INTEGER,
    blocking_count_after  INTEGER,
    created_at            TIMESTAMPTZ NOT NULL,
    sequence              BIGSERIAL,

    CONSTRAINT chk_task_state_history_event_type
        CHECK (event_type IN ('STATUS_CHANGED', 'BLOCKED', 'UNBLOCKED')),
    CONSTRAINT chk_task_state_history_status_values
        CHECK ((from_status IS NULL OR from_status IN ('PENDING', 'ACTIVE', 'DONE'))
           AND (to_status IS NULL OR to_status IN ('PENDING', 'ACTIVE', 'DONE'))),
    -- Each event type carries only the columns that are meaningful for it: NULL always means
    -- "not applicable to this event", never "unknown".
    CONSTRAINT chk_task_state_history_shape CHECK (
        (event_type = 'STATUS_CHANGED'
            AND from_status IS NOT NULL AND to_status IS NOT NULL
            AND blocking_count_before IS NULL AND blocking_count_after IS NULL)
        OR
        (event_type = 'BLOCKED'
            AND from_status IS NULL AND to_status IS NULL
            AND blocking_count_before = 0 AND blocking_count_after > 0)
        OR
        (event_type = 'UNBLOCKED'
            AND from_status IS NULL AND to_status IS NULL
            AND blocking_count_before > 0 AND blocking_count_after = 0)
    )
);

-- created_at gives the chronological order; sequence is the deterministic tie-breaker for rows
-- created in the same timestamp (same reasoning as idx_dependency_history_task_id_created_at).
CREATE INDEX idx_task_state_history_task_id_created_at
    ON task_state_history(task_id, created_at DESC, sequence DESC);

-- Deliberately no FK to task: history must survive the task it refers to being deleted
-- (same as dependency_history).
