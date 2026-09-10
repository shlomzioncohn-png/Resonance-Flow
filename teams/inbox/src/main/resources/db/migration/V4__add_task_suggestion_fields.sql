ALTER TABLE tasks
    ADD COLUMN effort_estimate_confidence REAL,
    ADD COLUMN suggested_owner_user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN suggested_owner_confidence  REAL;

ALTER TABLE tasks
    ADD CONSTRAINT chk_tasks_effort_estimate_confidence_range
        CHECK (effort_estimate_confidence IS NULL OR effort_estimate_confidence BETWEEN 0 AND 1),
    ADD CONSTRAINT chk_tasks_suggested_owner_confidence_range
        CHECK (suggested_owner_confidence IS NULL OR suggested_owner_confidence BETWEEN 0 AND 1);

CREATE INDEX idx_tasks_suggested_owner_user_id ON tasks(suggested_owner_user_id);
