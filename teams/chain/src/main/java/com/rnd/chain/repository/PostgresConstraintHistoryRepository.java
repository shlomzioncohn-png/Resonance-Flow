package com.rnd.chain.repository;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.ConstraintHistoryAction;
import com.rnd.chain.dto.ConstraintHistoryEntry;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Postgres-backed {@link ConstraintHistoryRepository}, plain JDBC like {@link PostgresConstraintRepository}.
 * Registered via {@code ConstraintRepositoryConfig}, not component-scanned.
 */
public class PostgresConstraintHistoryRepository implements ConstraintHistoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresConstraintHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(ConstraintHistoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        jdbcTemplate.update(
            "INSERT INTO constraint_history "
                + "(id, constraint_id, task_id, action, previous_status, new_status, source_ref, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            entry.id(),
            entry.constraintId(),
            entry.taskId(),
            entry.action().name(),
            entry.previousStatus() == null ? null : entry.previousStatus().name(),
            entry.newStatus().name(),
            entry.sourceRef(),
            OffsetDateTime.ofInstant(entry.createdAt(), ZoneOffset.UTC));
    }

    @Override
    public List<ConstraintHistoryEntry> findByTaskId(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return jdbcTemplate.query(
            "SELECT id, constraint_id, task_id, action, previous_status, new_status, source_ref, created_at "
                + "FROM constraint_history WHERE task_id = ? ORDER BY created_at DESC, sequence DESC",
            this::mapRow, taskId);
    }

    private ConstraintHistoryEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        String previous = rs.getString("previous_status");
        return new ConstraintHistoryEntry(
            UUID.fromString(rs.getString("id")),
            rs.getString("constraint_id"),
            UUID.fromString(rs.getString("task_id")),
            ConstraintHistoryAction.valueOf(rs.getString("action")),
            previous == null ? null : Constraint.ConstraintStatus.valueOf(previous),
            Constraint.ConstraintStatus.valueOf(rs.getString("new_status")),
            rs.getString("source_ref"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
