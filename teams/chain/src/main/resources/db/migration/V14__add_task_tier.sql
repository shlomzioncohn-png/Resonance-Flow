-- Mind's tier for a task (task.score.proposed). Nullable: unset until Mind classifies the task.
ALTER TABLE task
    ADD COLUMN tier VARCHAR(16);

ALTER TABLE task
    ADD CONSTRAINT chk_task_tier
        CHECK (tier IS NULL OR tier IN ('OPERATIONAL', 'TACTICAL', 'STRATEGIC'));
