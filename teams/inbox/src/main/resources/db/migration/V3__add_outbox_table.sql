CREATE TYPE outbox_status AS ENUM (
    'pending',
    'published',
    'failed'
);

CREATE TABLE outbox (
                        event_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        event_type      VARCHAR(100) NOT NULL,
                        aggregate_id    UUID,
                        message_key     VARCHAR(255) NOT NULL,
                        payload         JSONB NOT NULL,
                        metadata        JSONB,
                        correlation_id  UUID,
                        status          outbox_status NOT NULL DEFAULT 'pending',
                        retry_count     SMALLINT NOT NULL DEFAULT 0,
                        last_error      TEXT,
                        next_retry_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                        created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                        published_at    TIMESTAMPTZ,

                        CONSTRAINT chk_outbox_retry_count CHECK (retry_count >= 0),
                        CONSTRAINT chk_outbox_published_at CHECK (status <> 'published' OR published_at IS NOT NULL)
);

-- Matches the worker's pickup query: WHERE status = 'pending' AND next_retry_at <= now()
-- A partial index keeps it small - published/failed rows are never scanned by the worker.
CREATE INDEX idx_outbox_pending_pickup ON outbox (next_retry_at) WHERE status = 'pending';

-- Lets us find every outbox row for a given business entity (e.g. a task) during debugging.
CREATE INDEX idx_outbox_aggregate_id ON outbox (aggregate_id) WHERE aggregate_id IS NOT NULL;

-- Used by the cleanup job to find old published/failed rows to delete.
CREATE INDEX idx_outbox_status_created_at ON outbox (status, created_at);

-- Lets us find every outbox row that came from the same originating request,
-- to cross-reference with audit_log.correlation_id during debugging.
CREATE INDEX idx_outbox_correlation_id ON outbox (correlation_id) WHERE correlation_id IS NOT NULL;