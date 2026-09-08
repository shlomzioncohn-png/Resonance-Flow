package com.rnd.chain.repository;

import java.util.function.Supplier;

/**
 * Represents a transaction boundary spanning multiple repositories,
 * without depending on any real database. The seam that lets
 * {@code TaskDependencyService} write to both the dependency graph and the
 * history log as one unit today (in-memory, best-effort) and be switched
 * to a real {@code @Transactional} implementation later without changing
 * the service's call sites.
 */
public interface TransactionRunner {

    /**
     * Runs {@code action} such that its effects across multiple
     * repositories become visible together, or (best-effort, in the
     * absence of a real transactional store) not at all if it throws.
     */
    <T> T runInTransaction(Supplier<T> action);
}
