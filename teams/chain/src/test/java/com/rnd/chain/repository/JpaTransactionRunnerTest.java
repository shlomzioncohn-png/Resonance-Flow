package com.rnd.chain.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real transaction semantics of {@link JpaTransactionRunner}, using the processed_events table as scratch space. */
@Tag("postgres")
@SpringBootTest
class JpaTransactionRunnerTest {

    @Autowired
    private JpaTransactionRunner runner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID rowId = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM processed_events WHERE event_id = ?", rowId);
    }

    private void insertRow() {
        jdbcTemplate.update("INSERT INTO processed_events (event_id, event_type, processed_at) VALUES (?, 't', ?)",
                rowId, java.sql.Timestamp.from(Instant.now()));
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                Integer.class, rowId);
    }

    @Test
    void runInTransaction_returnsTheActionsResult() {
        assertThat(runner.runInTransaction(() -> "value")).isEqualTo("value");
    }

    @Test
    void runInTransaction_runsInsideAnActiveTransaction() {
        assertThat(runner.runInTransaction(TransactionSynchronizationManager::isActualTransactionActive)).isTrue();
    }

    @Test
    void runInTransaction_commitsTheActionsWrites() {
        runner.runInTransaction(() -> {
            insertRow();
            return null;
        });

        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void runInTransaction_rollsBackWritesAndPropagatesTheException_whenTheActionThrows() {
        RuntimeException failure = new RuntimeException("boom");

        assertThatThrownBy(() -> runner.runInTransaction(() -> {
            insertRow();
            throw failure;
        })).isSameAs(failure);

        assertThat(rowCount()).isZero();
    }

    @Test
    void runInReadOnlyTransaction_returnsTheActionsResult() {
        assertThat(runner.runInReadOnlyTransaction(() -> "value")).isEqualTo("value");
    }

    @Test
    void runInReadOnlyTransaction_rejectsWrites() {
        assertThatThrownBy(() -> runner.runInReadOnlyTransaction(() -> {
            insertRow();
            return null;
        })).isInstanceOf(RuntimeException.class);

        assertThat(rowCount()).isZero();
    }
}
