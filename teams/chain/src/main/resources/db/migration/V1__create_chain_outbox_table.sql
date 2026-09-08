CREATE TABLE chain_outbox (
    event_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(100) NOT NULL,
    aggregate_id    UUID,
    message_key     VARCHAR(255) NOT NULL,
    payload         JSONB NOT NULL,
    correlation_id  UUID,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count     SMALLINT NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_retry_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,

    CONSTRAINT chk_chain_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT chk_chain_outbox_retry_count CHECK (retry_count >= 0),
    CONSTRAINT chk_chain_outbox_published_at CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

-- Matches ChainOutboxEventRepository#claimPendingBatch: WHERE status = 'PENDING' AND next_retry_at <= now().
-- A partial index keeps it small - published/failed rows are never scanned by the worker.
CREATE INDEX idx_chain_outbox_pending_pickup ON chain_outbox (next_retry_at) WHERE status = 'PENDING';

-- Lets us find every outbox row for a given Chain-owned entity during debugging.
CREATE INDEX idx_chain_outbox_aggregate_id ON chain_outbox (aggregate_id) WHERE aggregate_id IS NOT NULL;

-- Used by ChainOutboxCleanupJob to find old published/failed rows to delete.
CREATE INDEX idx_chain_outbox_status_created_at ON chain_outbox (status, created_at);

-- Lets us cross-reference every outbox row that came from the same originating request.
CREATE INDEX idx_chain_outbox_correlation_id ON chain_outbox (correlation_id) WHERE correlation_id IS NOT NULL;
