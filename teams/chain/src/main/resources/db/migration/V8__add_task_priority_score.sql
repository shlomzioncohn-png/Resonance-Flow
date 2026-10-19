-- Issue #445: priority_score. Mind's confidence in its own score for a task (confidence), the
-- feasibility-adjusted score PriorityScoreService computes (priority_score), and Mind's
-- confidence in a specific dependency edge (task_dependency.confidence).

ALTER TABLE task
    ADD COLUMN confidence     DOUBLE PRECISION,
    ADD COLUMN priority_score DOUBLE PRECISION;

ALTER TABLE task
    ADD CONSTRAINT chk_task_confidence_range
        CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    ADD CONSTRAINT chk_task_priority_score_range
        CHECK (priority_score IS NULL OR priority_score BETWEEN 1 AND 10);

-- Nullable, same as Task.confidence - a Double in the DTO, matching the per-task field. Backfilled
-- below rather than given a column DEFAULT, so the backfill is explicit and a future column
-- addition to this table doesn't silently inherit it.
ALTER TABLE task_dependency
    ADD COLUMN confidence DOUBLE PRECISION;

-- Every edge that exists before this migration was added without any notion of Mind confidence
-- (either directly by a user, or by Mind before #445 - confidence was received and ignored, see
-- dependency-proposed-event.proto's pre-#445 history) - treat those as fully trusted, matching
-- TaskDependency's own two-arg-constructor default.
UPDATE task_dependency SET confidence = 1.0 WHERE confidence IS NULL;

ALTER TABLE task_dependency
    ADD CONSTRAINT chk_task_dependency_confidence_range
        CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1);
