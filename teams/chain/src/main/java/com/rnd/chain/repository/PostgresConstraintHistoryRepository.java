package com.rnd.chain.repository;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.ConstraintHistoryAction;
import com.rnd.chain.dto.ConstraintHistoryEntry;
import com.rnd.chain.dto.PageResponse;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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

    // Stand-ins for a null (open) bound; well inside timestamptz's range.
    private static final Instant OPEN_FROM = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant OPEN_TO = Instant.parse("9999-12-31T23:59:59Z");

    private final JdbcTemplate jdbcTemplate;

    public PostgresConstraintHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(ConstraintHistoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        jdbcTemplate.update(
            "INSERT INTO constraint_history "
                + "(constraint_id, task_id, action, previous_status, new_status, source_ref, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
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

    @Override
    public PageResponse<ConstraintHistoryEntry> findByTaskId(UUID taskId, Instant from, Instant to, int page,
                                                             int size) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        OffsetDateTime lower = OffsetDateTime.ofInstant(from == null ? OPEN_FROM : from, ZoneOffset.UTC);
        OffsetDateTime upper = OffsetDateTime.ofInstant(to == null ? OPEN_TO : to, ZoneOffset.UTC);

        long total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM constraint_history WHERE task_id = ? AND created_at >= ? AND created_at < ?",
            Long.class, taskId, lower, upper);
        List<ConstraintHistoryEntry> content = jdbcTemplate.query(
            "SELECT id, constraint_id, task_id, action, previous_status, new_status, source_ref, created_at "
                + "FROM constraint_history WHERE task_id = ? AND created_at >= ? AND created_at < ? "
                + "ORDER BY created_at DESC, sequence DESC LIMIT ? OFFSET ?",
            this::mapRow, taskId, lower, upper, size, (long) page * size);
        int totalPages = (int) ((total + size - 1) / size);
        return new PageResponse<>(content, page, size, total, totalPages);
    }

    @Override
    public boolean existsByTaskId(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM constraint_history WHERE task_id = ?)", Boolean.class, taskId));
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
