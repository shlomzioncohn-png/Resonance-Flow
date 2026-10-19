package com.rnd.chain.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Removes the rows a non-transactional test (for example one driven through a Kafka listener thread, whose
 * writes commit) left behind. Takes a snapshot of existing task and processed-event ids up front and, on
 * {@link #restore()}, deletes only ids that appeared since - so run these tests against a disposable
 * database, not one other work is writing to at the same time.
 */
public final class PgCleanup {

    private final JdbcTemplate jdbc;
    private final Set<UUID> tasksBefore;
    private final Set<UUID> eventsBefore;

    public PgCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.tasksBefore = ids("SELECT id FROM task");
        this.eventsBefore = ids("SELECT event_id FROM processed_events");
    }

    public void restore() {
        Set<UUID> newTasks = ids("SELECT id FROM task");
        newTasks.removeAll(tasksBefore);
        Set<UUID> newEvents = ids("SELECT event_id FROM processed_events");
        newEvents.removeAll(eventsBefore);

        for (UUID id : newTasks) {
            jdbc.update("DELETE FROM dependency_history WHERE task_id = ? OR depends_on_task_id = ?", id, id);
            jdbc.update("DELETE FROM task_dependency WHERE task_id = ? OR depends_on_task_id = ?", id, id);
            jdbc.update("DELETE FROM unresolved_dependencies WHERE task_id = ? OR depends_on_task_id = ?", id, id);
            jdbc.update("DELETE FROM task_constraint WHERE task_id = ?", id);
            jdbc.update("DELETE FROM task_state_history WHERE task_id = ?", id);
            jdbc.update("UPDATE task SET parent_task_id = NULL WHERE parent_task_id = ?", id);
        }
        for (UUID id : newTasks) {
            jdbc.update("DELETE FROM task WHERE id = ?", id);
        }
        for (UUID id : newEvents) {
            jdbc.update("DELETE FROM processed_events WHERE event_id = ?", id);
        }
    }

    private Set<UUID> ids(String sql) {
        List<UUID> rows = jdbc.queryForList(sql, UUID.class);
        return new HashSet<>(rows);
    }
}
