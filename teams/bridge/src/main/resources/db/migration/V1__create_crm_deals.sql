-- Mirrors the Deal record. The feed is append-only stage history, so
-- deal_id is not unique on its own: a deal appears once per stage
-- transition. The surrogate id is the primary key; (deal_id, stage_changed_at)
-- is unique instead, since a single deal cannot change stage twice at the
-- same instant. That unique constraint also provides the index every
-- DealRepository query filters or orders by - no separate index needed.
CREATE TABLE crm_deals (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deal_id           TEXT NOT NULL,
    title             TEXT,
    description       TEXT,
    stage             TEXT NOT NULL,
    owner_email       TEXT,
    owner_name        TEXT,
    close_date        DATE,
    deal_value        NUMERIC(15, 2),
    currency          TEXT,
    crm_account_slug  TEXT,
    source_url        TEXT,
    created_at        TIMESTAMP WITH TIME ZONE,
    stage_changed_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_crm_deals_deal_id_stage_changed_at UNIQUE (deal_id, stage_changed_at)
);
