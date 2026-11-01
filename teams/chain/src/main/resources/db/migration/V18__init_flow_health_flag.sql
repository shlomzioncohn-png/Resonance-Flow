-- Flow-health flags: patterns over time found by the analytics job (teams/chain/analytics/flow_health).
-- Read-only for the API; the job is the only writer and replaces the previous run's flags in one
-- transaction. Every flag belongs to one tenant and the API only ever returns the caller's own.
-- Deliberately no FK to task/dependency: entity_ids holds task ids, "a>b" edge ids and ordered
-- chains, and a flag must survive soft-deleted tasks.
CREATE TABLE flow_health_flag (
    tenant_id   UUID         NOT NULL,
    flag_id     VARCHAR(100) NOT NULL,
    pattern     VARCHAR(30)  NOT NULL,
    entity_type VARCHAR(20)  NOT NULL,
    entity_ids  JSONB        NOT NULL,
    severity    VARCHAR(10)  NOT NULL,
    score       DOUBLE PRECISION NOT NULL,
    rank        INTEGER      NOT NULL,
    explanation TEXT         NOT NULL,
    evidence    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    run_at      TIMESTAMPTZ  NOT NULL,

    -- flag_id is stable between runs, but two tenants can produce the same one.
    PRIMARY KEY (tenant_id, flag_id),
    CONSTRAINT chk_flow_health_flag_pattern CHECK (pattern IN ('CHRONIC_BLOCKER', 'RECURRING_CYCLE', 'SLOW_CHAIN')),
    CONSTRAINT chk_flow_health_flag_entity_type CHECK (entity_type IN ('task', 'edge', 'edge_pair', 'chain')),
    CONSTRAINT chk_flow_health_flag_severity CHECK (severity IN ('low', 'medium', 'high')),
    CONSTRAINT chk_flow_health_flag_score CHECK (score >= 0 AND score <= 1),
    CONSTRAINT chk_flow_health_flag_rank CHECK (rank >= 1)
);

CREATE INDEX idx_flow_health_flag_tenant_rank ON flow_health_flag(tenant_id, rank, flag_id);
CREATE INDEX idx_flow_health_flag_tenant_pattern_severity ON flow_health_flag(tenant_id, pattern, severity);
