CREATE TABLE task (
    id                      UUID PRIMARY KEY,
    parent_task_id          UUID REFERENCES task(id) ON DELETE SET NULL,
    owner_id                VARCHAR(255) NOT NULL,
    status                  VARCHAR(20) NOT NULL,
    is_ready                BOOLEAN NOT NULL DEFAULT false,
    blocking_count          INTEGER NOT NULL DEFAULT 0,
    open_constraint_count   INTEGER NOT NULL DEFAULT 0,
    estimated_duration_min  INTEGER,
    score                   DOUBLE PRECISION,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMP NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP NOT NULL DEFAULT now(),
    deleted_at              TIMESTAMP,

    CONSTRAINT chk_task_status CHECK (status IN ('PENDING', 'ACTIVE', 'DONE')),
    CONSTRAINT chk_task_blocking_count_non_negative CHECK (blocking_count >= 0),
    CONSTRAINT chk_task_open_constraint_count_non_negative CHECK (open_constraint_count >= 0)
);

CREATE INDEX idx_task_parent_task_id ON task(parent_task_id);

CREATE TABLE task_dependency (
    task_id             UUID NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    depends_on_task_id  UUID NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (task_id, depends_on_task_id),
    CONSTRAINT chk_task_dependency_no_self_reference CHECK (task_id <> depends_on_task_id)
);

CREATE INDEX idx_task_dependency_depends_on_task_id ON task_dependency(depends_on_task_id);

-- Closes issue #243 (TOCTOU between addDependency's existence check and a concurrent soft
-- delete): the FK constraints above only guarantee the referenced *row* still exists, not that
-- it's still non-soft-deleted - a soft delete is an UPDATE (sets deleted_at), not a DELETE, so it
-- never trips the FK. This trigger closes that specific gap at the DB level, atomically, as part
-- of the same INSERT the edge itself uses - no separate lock/check round trip needed.
CREATE FUNCTION fn_check_task_dependency_tasks_active() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM task WHERE id = NEW.task_id AND deleted_at IS NOT NULL) THEN
        RAISE EXCEPTION 'task % is soft-deleted, cannot participate in a dependency edge', NEW.task_id;
    END IF;
    IF EXISTS (SELECT 1 FROM task WHERE id = NEW.depends_on_task_id AND deleted_at IS NOT NULL) THEN
        RAISE EXCEPTION 'task % is soft-deleted, cannot participate in a dependency edge', NEW.depends_on_task_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_task_dependency_check_tasks_active
    BEFORE INSERT OR UPDATE ON task_dependency
    FOR EACH ROW
    EXECUTE FUNCTION fn_check_task_dependency_tasks_active();
