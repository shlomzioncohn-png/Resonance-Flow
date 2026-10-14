-- Issue #580: persist tenant_id on task, and reject a dependency edge between two tasks of
-- different tenants - a cross-tenant edge would let one tenant reach another tenant's task data
-- through Chain's traversal endpoints (/ancestors, /descendants, /dependents).
--
-- Nullable to begin with: existing rows predate this column, and tenant_id is only populated from
-- here on by TaskCreatedEventHandler/TaskUpdatedEventHandler (Inbox owns tenancy). Tracked
-- separately to make it NOT NULL once every row is populated through those consumers.
ALTER TABLE task ADD COLUMN tenant_id UUID;

CREATE INDEX idx_task_tenant_id ON task (tenant_id);

-- Same shape as fn_check_task_dependency_tasks_active (V1): a BEFORE INSERT/UPDATE trigger on
-- task_dependency, atomic with the edge's own write - no separate lock/check round trip. Mirrors
-- the application-level check in TaskEligibilityService (belt-and-suspenders), with the same
-- strict policy: an edge is only allowed when both tasks have a non-null, equal tenant_id - a
-- null tenant_id on either side is rejected, not skipped, since it cannot yet be proven safe.
--
-- A task that doesn't exist at all is deliberately NOT this trigger's concern: BEFORE ROW triggers
-- fire before the FK constraint/CHECK (chk_task_dependency_no_self_reference) are evaluated for
-- the row, so without this guard, a missing-task or self-reference insert would be rejected with
-- this trigger's generic exception instead of the FK/CHECK violation's more specific one (same
-- precedent as fn_check_task_dependency_tasks_active's own EXISTS(...) check, which is false, not
-- true, for a task id that isn't there at all).
CREATE FUNCTION fn_check_task_dependency_same_tenant() RETURNS TRIGGER AS $$
DECLARE
    task_tenant_id UUID;
    task_exists BOOLEAN;
    depends_on_tenant_id UUID;
    depends_on_exists BOOLEAN;
BEGIN
    SELECT tenant_id INTO task_tenant_id FROM task WHERE id = NEW.task_id;
    task_exists := FOUND;
    SELECT tenant_id INTO depends_on_tenant_id FROM task WHERE id = NEW.depends_on_task_id;
    depends_on_exists := FOUND;

    IF NOT task_exists OR NOT depends_on_exists THEN
        RETURN NEW;
    END IF;

    IF task_tenant_id IS NULL OR depends_on_tenant_id IS NULL OR task_tenant_id <> depends_on_tenant_id THEN
        RAISE EXCEPTION 'cross-tenant dependency edge rejected: task % (tenant %) -> task % (tenant %)',
            NEW.task_id, task_tenant_id, NEW.depends_on_task_id, depends_on_tenant_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_task_dependency_check_same_tenant
    BEFORE INSERT OR UPDATE ON task_dependency
    FOR EACH ROW
    EXECUTE FUNCTION fn_check_task_dependency_same_tenant();
