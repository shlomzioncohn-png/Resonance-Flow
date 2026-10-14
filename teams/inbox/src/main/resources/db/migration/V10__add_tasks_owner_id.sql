ALTER TABLE tasks
    ADD COLUMN owner_id UUID REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX idx_tasks_owner_id ON tasks(owner_id);
