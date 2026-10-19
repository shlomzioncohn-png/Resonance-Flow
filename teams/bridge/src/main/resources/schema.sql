-- control-plane state (GroupStateStore/CursorStore/CalendarEventStateStore), moved off MinIO.
-- Plain key -> blob, deliberately not relational: value stays opaque JSON bytes, matching
-- ObjectStorageClient's bytes-in/bytes-out contract - see storage.PostgresObjectStorageClient.
CREATE TABLE IF NOT EXISTS control_plane_state (
    key        TEXT PRIMARY KEY,
    value      BYTEA NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
