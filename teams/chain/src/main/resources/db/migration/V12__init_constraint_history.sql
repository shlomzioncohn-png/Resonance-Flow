CREATE TABLE constraint_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    constraint_id   VARCHAR(255) NOT NULL,
    task_id         UUID NOT NULL,
    action          VARCHAR(20) NOT NULL,
    previous_status VARCHAR(50),
    new_status      VARCHAR(50) NOT NULL,
    source_ref      VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL,
    sequence        BIGSERIAL,

    CONSTRAINT chk_constraint_history_action CHECK (action IN ('CREATED', 'STATUS_CHANGED')),
    CONSTRAINT chk_constraint_history_previous_status CHECK (previous_status IN ('PENDING', 'SATISFIED', 'WAIVED')),
    CONSTRAINT chk_constraint_history_new_status CHECK (new_status IN ('PENDING', 'SATISFIED', 'WAIVED'))
);

-- sequence breaks ties between entries written in the same timestamp.
CREATE INDEX idx_constraint_history_task_id_created_at
    ON constraint_history(task_id, created_at DESC, sequence DESC);

-- Deliberately no FK: the audit trail must survive deletion of the constraint or task.
