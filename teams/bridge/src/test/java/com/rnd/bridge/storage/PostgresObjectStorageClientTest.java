package com.rnd.bridge.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises {@link PostgresObjectStorageClient} against a real Postgres (Testcontainers) rather
 * than a mocked JdbcTemplate - same reasoning this codebase already applies to Kafka-touching
 * code never hitting a real broker in tests (see teams/bridge/CLAUDE.md §7), just the DB-side
 * mirror of it: the thing worth proving here is the actual SQL (the upsert's ON CONFLICT
 * clause), which a mock can't verify.
 */
@Testcontainers
class PostgresObjectStorageClientTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:14-alpine");

    private static HikariDataSource dataSource;
    private static PostgresObjectStorageClient client;

    @BeforeAll
    static void startContainerAndCreateSchema() {
        POSTGRES.start();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        dataSource = new HikariDataSource(config);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE control_plane_state (
                    key        TEXT PRIMARY KEY,
                    value      BYTEA NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        client = new PostgresObjectStorageClient(jdbcTemplate);
    }

    @AfterEach
    void clearTable() {
        new JdbcTemplate(dataSource).execute("DELETE FROM control_plane_state");
    }

    @AfterAll
    static void stopContainer() {
        dataSource.close();
        POSTGRES.stop();
    }

    @Test
    void getObjectIsEmptyWhenKeyDoesNotExist() {
        assertThat(client.getObject("missing-key")).isEmpty();
    }

    @Test
    void putObjectThenGetObjectRoundTripsTheSameBytes() {
        byte[] content = "{\"groupId\":\"g1\"}".getBytes(StandardCharsets.UTF_8);

        client.putObject("_groups/SLACK/tenant-1/g1.json", content, "application/json", Map.of());

        assertThat(client.getObject("_groups/SLACK/tenant-1/g1.json")).contains(content);
    }

    @Test
    void putObjectOnAnExistingKeyOverwritesRatherThanDuplicates() {
        String key = "_cursors/SLACK/tenant-1.json";
        client.putObject(key, "first".getBytes(StandardCharsets.UTF_8), "application/json", Map.of());

        client.putObject(key, "second".getBytes(StandardCharsets.UTF_8), "application/json", Map.of());

        assertThat(client.getObject(key)).contains("second".getBytes(StandardCharsets.UTF_8));
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT count(*) FROM control_plane_state WHERE key = ?", Integer.class, key))
                .isEqualTo(1);
    }
}
