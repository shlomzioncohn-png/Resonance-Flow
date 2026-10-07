-- Issue #378: durable storage for Constraint, replacing the bare openConstraintCount counter.
--
-- Table is named task_constraint, not "constraint" - CONSTRAINT is a reserved SQL keyword, and
-- this also matches Chain's own task_dependency naming convention (see issue #377) instead of
-- Inbox's plural "tasks" style.
--
-- References task(id), created by V1__init_chain_schema.sql (issue #377). Runs after V2 so the
-- version order matches the dependency.
CREATE TABLE task_constraint (
    constraint_id VARCHAR(255) PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES task (id),
    type VARCHAR(50) NOT NULL CHECK (type IN ('APPROVAL_NEEDED', 'BLOCKED_BY_INFO', 'DATE_WINDOW')),
    status VARCHAR(50) NOT NULL CHECK (status IN ('PENDING', 'SATISFIED', 'WAIVED')),
    reason JSONB,
    source_ref VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- Constraints are always looked up per task (findByTaskId) and openConstraintCount is meant to
-- be derived from this table (issue #378, "prefer deriving ... from a real query"), so this
-- index is on the hot path, not a nice-to-have.
CREATE INDEX idx_task_constraint_task_id ON task_constraint (task_id);
