package com.rnd.chain.events;

import com.rnd.chain.dto.ProcessedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

/** Postgres-backed {@link ProcessedEventStore}: the primary key makes the claim atomic across threads and instances. */
@Repository
public class PostgresProcessedEventStore implements ProcessedEventStore {

    private final JdbcTemplate jdbcTemplate;

    public PostgresProcessedEventStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean tryClaim(ProcessedEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        // Exactly one concurrent caller inserts the row; every other caller conflicts and gets 0.
        int inserted = jdbcTemplate.update(
                "INSERT INTO processed_events (event_id, event_type, processed_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT (event_id) DO NOTHING",
                event.eventId(), event.eventType(), Timestamp.from(event.processedAt()));
        return inserted == 1;
    }

    @Override
    public void release(UUID eventId) {
        jdbcTemplate.update("DELETE FROM processed_events WHERE event_id = ?", eventId);
    }
}
