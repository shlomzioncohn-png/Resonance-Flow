package com.rnd.chain.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresGraphLockTest {

    private final List<String> calls = new ArrayList<>();

    private final JdbcTemplate recordingJdbcTemplate = new JdbcTemplate() {
        @Override
        public void query(String sql, RowCallbackHandler rch, Object... args) {
            calls.add(sql + " " + List.of(args));
        }
    };

    private final PostgresGraphLock graphLock = new PostgresGraphLock(recordingJdbcTemplate);

    @AfterEach
    void clearTransactionFlag() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void runExclusive_throwsAndSkipsAction_whenNoTransactionIsActive() {
        assertThatThrownBy(() -> graphLock.runExclusive(() -> calls.add("action")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(calls).isEmpty();
    }

    @Test
    void runExclusive_takesAdvisoryLockBeforeAction_whenTransactionIsActive() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        String result = graphLock.runExclusive(() -> {
            calls.add("action");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(calls).containsExactly(
                "SELECT pg_advisory_xact_lock(?) [" + PostgresGraphLock.GRAPH_LOCK_KEY + "]",
                "action");
    }
}
