package com.rnd.chain.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Transaction-scoped Postgres advisory lock.
 *
 * <p>The lock is scoped per tenant, so graph writes for different tenants
 * can proceed concurrently, while writes for the same tenant remain serialized.
 *
 * <p>Tasks without a tenant share the legacy global graph lock key.
 */
@Component
public class PostgresGraphLock implements GraphLock {

    /**
     * Legacy shared key for tasks whose tenantId is null.
     */
    static final long GRAPH_LOCK_KEY = 0x43484149_4E475248L;

    /**
     * Namespace used when deriving advisory-lock keys from tenant IDs.
     */
    static final long TENANT_LOCK_NAMESPACE = 0x54454E41_4E544C4BL;

    private final JdbcTemplate jdbcTemplate;

    public PostgresGraphLock(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public <T> T runExclusive(
            UUID tenantId,
            Supplier<T> action) {

        if (!TransactionSynchronizationManager
                .isActualTransactionActive()) {

            throw new IllegalStateException(
                    "GraphLock must be taken inside a transaction");
        }

        long lockKey =
                lockKeyForTenant(tenantId);

        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(?)",
                (RowCallbackHandler) rs -> {
                },
                lockKey);

        return action.get();
    }

    static long lockKeyForTenant(
            UUID tenantId) {

        if (tenantId == null) {
            return GRAPH_LOCK_KEY;
        }

        return tenantId.getMostSignificantBits()
                ^ tenantId.getLeastSignificantBits()
                ^ TENANT_LOCK_NAMESPACE;
    }
}