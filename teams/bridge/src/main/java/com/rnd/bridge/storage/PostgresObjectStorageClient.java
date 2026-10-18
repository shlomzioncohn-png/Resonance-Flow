package com.rnd.bridge.storage;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Backs the control-plane lane (ControlPlaneStateStore, CursorStore, GroupStateStore,
 * CalendarEventStateStore) with a plain key-&gt;blob table instead of MinIO - these are small,
 * frequently read/written records, not bulk content, so a single-row upsert/select beats an
 * object-store PUT/GET for this workload. Deliberately NOT a relational redesign: `key`/`value`
 * stay opaque to Postgres, exactly like an object key/body pair in MinIO - see
 * control_plane_state's own schema comment for why.
 * contentType/tags are accepted to satisfy {@link ObjectStorageClient} but have no column here:
 * every caller in this codebase always passes "application/json" / Map.of(), and nothing reads
 * either back.
 */
@Service
public class PostgresObjectStorageClient implements ObjectStorageClient {

    private final JdbcTemplate jdbcTemplate;

    public PostgresObjectStorageClient(JdbcTemplate controlPlaneJdbcTemplate) {
        this.jdbcTemplate = controlPlaneJdbcTemplate;
    }

    @Override
    public PutResult putObject(String objectKey, byte[] content, String contentType, Map<String, String> tags) {
        jdbcTemplate.update(
                """
                INSERT INTO control_plane_state (key, value, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at
                """,
                objectKey, content, Timestamp.from(Instant.now()));
        return new PutResult(contentType, content.length);
    }

    @Override
    public Optional<byte[]> getObject(String objectKey) {
        byte[] value = jdbcTemplate.query(
                "SELECT value FROM control_plane_state WHERE key = ?",
                rs -> rs.next() ? rs.getBytes("value") : null,
                objectKey);
        return Optional.ofNullable(value);
    }
}
