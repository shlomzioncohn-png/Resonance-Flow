-- Personal AI insights shown on the employee's task screen (see Inbox_Data_Schema_Specification.md §3.12).
-- One row is one insight card: the insight sentence, an optional recommendation and the evidence it rests on.
-- Rows are only created and read - never updated - so there is no updated_at.
-- Only insights that passed verification are stored, and an analysis that found nothing (NONE) is not stored at all,
-- which is why insight_type has no 'none' value.

CREATE TYPE insight_type AS ENUM (
    'reinforcement',
    'trend',
    'improvement_opportunity'
);

CREATE TABLE insights (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id              UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    insight_type         insight_type NOT NULL,
    insight_text         TEXT NOT NULL,
    recommendation_text  TEXT,
    evidence_text        TEXT NOT NULL,
    period_from          DATE NOT NULL,
    period_to            DATE NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_insights_period CHECK (period_from <= period_to)
);

-- Serves the employee's insight list: their own insights, newest first
-- (WHERE user_id = ? ORDER BY created_at DESC, id DESC). id is the tie-breaker for rows created at the same instant.
CREATE INDEX idx_insights_user_created_at_id ON insights (user_id, created_at DESC, id DESC);
