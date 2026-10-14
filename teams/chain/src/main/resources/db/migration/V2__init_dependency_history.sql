CREATE TABLE dependency_history (
    id                  UUID PRIMARY KEY,
    task_id             UUID NOT NULL,
    depends_on_task_id  UUID NOT NULL,
    action              VARCHAR(10) NOT NULL,
    source              VARCHAR(10) NOT NULL,
    source_ref          VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL,
    sequence            BIGSERIAL,

    CONSTRAINT chk_dependency_history_action CHECK (action IN ('ADDED', 'REMOVED')),
    CONSTRAINT chk_dependency_history_source CHECK (source IN ('MIND', 'USER', 'SYSTEM'))
);

-- created_at gives the chronological order; sequence is a deterministic tie-breaker for entries
-- created in the same timestamp (two writes landing in the same millisecond would otherwise tie).
CREATE INDEX idx_dependency_history_task_id_created_at
    ON dependency_history(task_id, created_at DESC, sequence DESC);

-- Same dedup key as findBySourceAndSourceRef - null source_ref rows are exempt (USER changes
-- don't require one), matching the interface's "always false if sourceRef is null" contract.
CREATE UNIQUE INDEX uq_dependency_history_source_source_ref
    ON dependency_history(source, source_ref) WHERE source_ref IS NOT NULL;

-- Deliberately no FK from dependency_history to task: an audit trail must survive the task it
-- refers to being deleted later.
