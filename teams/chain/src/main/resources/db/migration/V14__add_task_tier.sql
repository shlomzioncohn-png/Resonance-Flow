-- Issue #864: latest tier Inbox sent for the task; NULL until Inbox classifies it. No backfill.
ALTER TABLE task
    ADD COLUMN tier VARCHAR(20),
    ADD CONSTRAINT chk_task_tier
        CHECK (tier IS NULL OR tier IN ('OPERATIONAL', 'TACTICAL', 'STRATEGIC'));
