package com.rnd.chain.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.chain.dto.FlowHealthEntityType;
import com.rnd.chain.dto.FlowHealthFlag;
import com.rnd.chain.dto.FlowHealthPattern;
import com.rnd.chain.dto.FlowHealthSeverity;
import com.rnd.chain.dto.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Postgres-backed {@link FlowHealthFlagRepository}, plain JDBC like {@link PostgresConstraintHistoryRepository}.
 * Registered via {@code FlowHealthRepositoryConfig}, not component-scanned.
 */
public class PostgresFlowHealthFlagRepository implements FlowHealthFlagRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresFlowHealthFlagRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResponse<FlowHealthFlag> findPage(FlowHealthPattern pattern, FlowHealthSeverity severity,
                                                 int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (pattern != null) {
            where.append(" AND pattern = ?");
            args.add(pattern.name());
        }
        if (severity != null) {
            where.append(" AND severity = ?");
            args.add(severity.value());
        }

        long total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flow_health_flag" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<FlowHealthFlag> content = jdbcTemplate.query(
            "SELECT flag_id, pattern, entity_type, entity_ids::text AS entity_ids, severity, score, rank, "
                + "explanation, evidence::text AS evidence, run_at FROM flow_health_flag" + where
                + " ORDER BY rank ASC, flag_id ASC LIMIT ? OFFSET ?",
            this::mapRow, pageArgs.toArray());
        int totalPages = (int) ((total + size - 1) / size);
        return new PageResponse<>(content, page, size, total, totalPages);
    }

    private FlowHealthFlag mapRow(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new FlowHealthFlag(
                rs.getString("flag_id"),
                FlowHealthPattern.valueOf(rs.getString("pattern")),
                FlowHealthEntityType.fromValue(rs.getString("entity_type")),
                objectMapper.readValue(rs.getString("entity_ids"), STRING_LIST),
                FlowHealthSeverity.fromValue(rs.getString("severity")),
                rs.getDouble("score"),
                rs.getInt("rank"),
                rs.getString("explanation"),
                objectMapper.readTree(rs.getString("evidence")),
                rs.getObject("run_at", OffsetDateTime.class).toInstant());
        } catch (JsonProcessingException e) {
            throw new SQLException("Malformed jsonb in flow_health_flag " + rs.getString("flag_id"), e);
        }
    }
}
