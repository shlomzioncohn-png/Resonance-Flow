package com.rnd.chain.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.chain.dto.Constraint;

import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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
 * <p>Registered as a bean via {@code ConstraintRepositoryConfig}, not component-scanned directly
 * (a {@code @Bean} factory method with {@code @ConditionalOnMissingBean}, not the annotation on
 * this class itself - the latter doesn't reliably self-register outside true auto-configuration
 * classes). Same reasoning as why {@code InMemoryTaskRepository} isn't a {@code @Component}
 * either; {@code MockDataConfig} wires it the same way.
 *
 * <p>{@code reason} is stored as {@code jsonb} - read back as plain text and parsed with Jackson,
 * written via a {@link PGobject} typed {@code "jsonb"} (required for the Postgres JDBC driver to
 * accept it - a plain String/null parameter is rejected as the wrong type for a jsonb column).
 *
 * <p>{@link #save} uses {@code xmax = 0} (Postgres's own way of reporting "this row was just
 * inserted, not updated by the ON CONFLICT branch") instead of a {@code SELECT COUNT(*)} or an
 * in-memory idempotency ledger - see teams/chain/CLAUDE.md "Key decisions" (2026-09-22) for why.
 * {@link #updateStatus} reads the row's previous status inside the same atomic statement (a CTE
 * under {@code FOR UPDATE}) rather than as a separate read, so no other writer can change the row
 * between the read and the write.
 */
public class PostgresConstraintRepository implements ConstraintRepository {

    private static final String JSONB_TYPE = "jsonb";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

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
    public boolean save(Constraint constraint) {
        Objects.requireNonNull(constraint, "constraint must not be null");
        // Upsert by constraint_id (issue #378: "reprocessing the same constraint event must not
        // create a duplicate row"). task_id/type/source_ref/created_at are identity/immutable
        // fields set only on first insert - only status/reason/updated_at can change afterwards
        // (a constraint's status transitions; its identity does not).
        //
        // "xmax = 0" is Postgres's own signal for "this row was inserted by this statement, not
        // updated via the ON CONFLICT branch" - it comes from the same statement that does the
        // write, at no extra query cost, and unlike checking "did this succeed" it actually
        // distinguishes the two cases: this upsert is idempotent by design, so it "succeeds"
        // identically whether the row was new or already existed.
        Boolean wasInserted = jdbcTemplate.queryForObject(
            "INSERT INTO task_constraint "
                + "(constraint_id, task_id, type, status, reason, source_ref, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (constraint_id) DO UPDATE SET "
                + "status = EXCLUDED.status, reason = EXCLUDED.reason, updated_at = EXCLUDED.updated_at "
                + "RETURNING (xmax = 0)",
            Boolean.class,
            constraint.getConstraintId(),
            constraint.getTaskId(),
            constraint.getType().name(),
            constraint.getStatus().name(),
            toJsonb(constraint.getReason()),
            constraint.getSourceRef(),
            Timestamp.valueOf(constraint.getCreatedAt()),
            Timestamp.valueOf(constraint.getUpdatedAt()));
        return Boolean.TRUE.equals(wasInserted);
    }

    @Override
    public Constraint.ConstraintStatus updateStatus(UUID taskId, String constraintId,
                                                      Constraint.ConstraintStatus newStatus,
                                                      Map<String, Object> reason, LocalDateTime updatedAt) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(constraintId, "constraintId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        // The CTE reads the row's current status under FOR UPDATE (locking it) before the UPDATE
        // below changes it, so the two happen as one atomic step - no other writer can change the
        // row in between, and no separate round-trip is needed to find the "before" value. The
        // task_id match is part of both the lock and the update, so a mismatched task matches no
        // row at all and nothing is written - the check and the write can't be separated.
        List<String> previous = jdbcTemplate.query(
            "WITH old AS (SELECT status FROM task_constraint "
                + "WHERE constraint_id = ? AND task_id = ? FOR UPDATE) "
                + "UPDATE task_constraint t SET status = ?, reason = ?, updated_at = ? "
                + "FROM old "
                + "WHERE t.constraint_id = ? AND t.task_id = ? "
                + "RETURNING old.status",
            (rs, rowNum) -> rs.getString(1),
            constraintId, taskId, newStatus.name(), toJsonb(reason), Timestamp.valueOf(updatedAt),
            constraintId, taskId);
        if (previous.isEmpty()) {
            throw new IllegalStateException("No constraint " + constraintId + " on task " + taskId);
        }
        return Constraint.ConstraintStatus.valueOf(previous.get(0));
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
