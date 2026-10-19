-- Issue #580: a cross-tenant dependency edge is rejected, not silently dropped - it must still be
-- traceable in dependency_history, so the existing action CHECK (ADDED/REMOVED only, V2 migration)
-- needs a third value.
ALTER TABLE dependency_history DROP CONSTRAINT chk_dependency_history_action;

ALTER TABLE dependency_history ADD CONSTRAINT chk_dependency_history_action
    CHECK (action IN ('ADDED', 'REMOVED', 'REJECTED'));
