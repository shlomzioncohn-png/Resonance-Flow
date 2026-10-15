package com.rnd.chain.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

/** Transaction-scoped Postgres advisory lock: held until the caller's transaction commits or rolls back. */
@Component
public class PostgresGraphLock implements GraphLock {

    /** Arbitrary constant; must not collide with another advisory-lock user in the same database. */
    static final long GRAPH_LOCK_KEY = 0x43484149_4E475248L;

    private final JdbcTemplate jdbcTemplate;

    public PostgresGraphLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public <T> T runExclusive(Supplier<T> action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("GraphLock must be taken inside a transaction");
        }
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?)", (RowCallbackHandler) rs -> { }, GRAPH_LOCK_KEY);
        return action.get();
    }
}
