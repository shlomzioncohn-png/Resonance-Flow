package com.rnd.chain.repository;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Serializes graph mutations per tenant.
 *
 * <p>Operations for the same tenant share the same lock and therefore execute
 * sequentially. Operations for different tenants may execute concurrently.
 *
 * <p>Each transaction must acquire at most one tenant graph lock.
 */
public interface GraphLock {

    <T> T runExclusive(
            UUID tenantId,
            Supplier<T> action);

    default void runExclusive(
            UUID tenantId,
            Runnable action) {

        runExclusive(
                tenantId,
                () -> {
                    action.run();
                    return null;
                });
    }
}