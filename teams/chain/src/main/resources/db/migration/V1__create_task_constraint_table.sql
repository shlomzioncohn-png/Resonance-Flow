-- Issue #378: durable storage for Constraint, replacing the bare openConstraintCount counter.
--
-- Table is named task_constraint, not "constraint" - CONSTRAINT is a reserved SQL keyword, and
-- this also matches Chain's own task_dependency naming convention (see issue #377) instead of
-- Inbox's plural "tasks" style.
--
-- NOTE: this migration references task(id), which does not exist yet - Task/TaskDependency
-- persistence is issue #377, still open with no branch started. Per team decision, this branch
-- writes Constraint persistence as if #377 already landed rather than building the task table
-- itself (not our work to do) - so this migration will fail to apply standalone until #377's
-- own migration creates the task table first. Accepted tradeoff - see teams/chain/CLAUDE.md.
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
