ALTER TABLE tasks
    ADD CONSTRAINT chk_tasks_priority_score_range
        CHECK (priority_score IS NULL OR priority_score BETWEEN 1 AND 10);
