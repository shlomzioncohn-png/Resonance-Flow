-- Readiness is derived only by the task_readiness view (V11); the stored copies are no longer
-- read or written (#917). Dropping a column also drops its single-column CHECK constraint.
ALTER TABLE task DROP COLUMN IF EXISTS is_ready;
ALTER TABLE task DROP COLUMN IF EXISTS blocking_count;
ALTER TABLE task DROP COLUMN IF EXISTS open_constraint_count;
