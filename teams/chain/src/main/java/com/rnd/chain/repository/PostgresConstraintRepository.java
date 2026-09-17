package com.rnd.chain.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.chain.dto.Constraint;

import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Postgres-backed {@link ConstraintRepository}, plain JDBC (no JPA/Hibernate) - matching the
 * hand-written-repository convention {@link TaskDependencyRepository}'s in-memory implementation
 * already follows in this module, per the team-lead decision on issue #378/#271 (2026-09-17):
 * Chain's repositories are hand-written interfaces, not Spring Data, and the operations needed
 * here (upsert, row locking) are raw SQL anyway.
 *
 * <p>{@code @ConditionalOnMissingBean} so a test double or future mock bean registered for
 * {@link ConstraintRepository} takes precedence, the same pattern
 * {@code MockDataConfig}'s {@code TaskRepository}/{@code TaskDependencyRepository} beans use.
 *
 * <p>{@code reason} is stored as {@code jsonb} - read back as plain text and parsed with Jackson,
 * written via a {@link PGobject} typed {@code "jsonb"} (required for the Postgres JDBC driver to
 * accept it - a plain String/null parameter is rejected as the wrong type for a jsonb column).
 */
@Repository
@ConditionalOnMissingBean(ConstraintRepository.class)
public class PostgresConstraintRepository implements ConstraintRepository {

    private static final String JSONB_TYPE = "jsonb";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public PostgresConstraintRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Constraint> findByTaskId(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return jdbcTemplate.query(
            "SELECT constraint_id, task_id, type, status, reason, source_ref, created_at, updated_at "
                + "FROM task_constraint WHERE task_id = ?",
            this::mapRow, taskId);
    }

    @Override
    public Optional<Constraint> findById(String constraintId) {
        Objects.requireNonNull(constraintId, "constraintId must not be null");
        List<Constraint> found = jdbcTemplate.query(
            "SELECT constraint_id, task_id, type, status, reason, source_ref, created_at, updated_at "
                + "FROM task_constraint WHERE constraint_id = ?",
            this::mapRow, constraintId);
        return found.stream().findFirst();
    }

    @Override
    public Constraint save(Constraint constraint) {
        Objects.requireNonNull(constraint, "constraint must not be null");
        // Upsert by constraint_id (issue #378: "reprocessing the same constraint event must not
        // create a duplicate row"). task_id/type/source_ref/created_at are identity/immutable
        // fields set only on first insert - only status/reason/updated_at can change afterwards
        // (a constraint's status transitions; its identity does not).
        jdbcTemplate.update(
            "INSERT INTO task_constraint "
                + "(constraint_id, task_id, type, status, reason, source_ref, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (constraint_id) DO UPDATE SET "
                + "status = EXCLUDED.status, reason = EXCLUDED.reason, updated_at = EXCLUDED.updated_at",
            constraint.getConstraintId(),
            constraint.getTaskId(),
            constraint.getType().name(),
            constraint.getStatus().name(),
            toJsonb(constraint.getReason()),
            constraint.getSourceRef(),
            Timestamp.valueOf(constraint.getCreatedAt()),
            Timestamp.valueOf(constraint.getUpdatedAt()));
        return constraint;
    }

    private Constraint mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Constraint(
            rs.getString("constraint_id"),
            UUID.fromString(rs.getString("task_id")),
            Constraint.ConstraintType.valueOf(rs.getString("type")),
            Constraint.ConstraintStatus.valueOf(rs.getString("status")),
            fromJsonb(rs.getString("reason")),
            rs.getString("source_ref"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private PGobject toJsonb(Map<String, Object> reason) {
        PGobject jsonb = new PGobject();
        jsonb.setType(JSONB_TYPE);
        try {
            jsonb.setValue(reason == null ? null : objectMapper.writeValueAsString(reason));
        } catch (JsonProcessingException | SQLException e) {
            throw new IllegalStateException("Failed to serialize Constraint.reason to JSONB", e);
        }
        return jsonb;
    }

    private Map<String, Object> fromJsonb(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize Constraint.reason from JSONB", e);
        }
    }
}
