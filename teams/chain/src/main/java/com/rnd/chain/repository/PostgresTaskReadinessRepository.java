package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskReadinessSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Array;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PostgresTaskReadinessRepository implements TaskReadinessRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresTaskReadinessRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<UUID, TaskReadinessSnapshot> findByIds(List<UUID> ids) {
        Objects.requireNonNull(ids, "ids must not be null");

        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }

        String sql = """
                SELECT id, tenant_id, blocking_count, open_constraint_count
                FROM task_readiness
                WHERE id = ANY (?)
                """;

        List<TaskReadinessSnapshot> snapshots = jdbcTemplate.query(
                connection -> {
                    var statement = connection.prepareStatement(sql);

                    Array idsArray = connection.createArrayOf(
                            "uuid",
                            ids.toArray(UUID[]::new)
                    );

                    statement.setArray(1, idsArray);
                    return statement;
                },
                (rs, rowNum) -> new TaskReadinessSnapshot(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getInt("blocking_count"),
                        rs.getInt("open_constraint_count")
                )
        );

        return snapshots.stream()
                .collect(Collectors.toMap(
                        TaskReadinessSnapshot::taskId,
                        Function.identity()
                ));
    }
}