package com.rnd.chain.repository;

import java.util.function.Supplier;

/** DB-independent transaction boundary seam, so the in-memory impl can later be swapped for a real one. */
public interface TransactionRunner {

    /** Runs {@code action}; best-effort atomicity across repositories. Exclusive: no concurrent
     * {@code runInTransaction}/{@code runInReadOnlyTransaction} call overlaps this one. */
    <T> T runInTransaction(Supplier<T> action);

    /** Runs a read-only {@code action}; can run concurrently with other read-only actions, but
     * still waits for any in-flight {@link #runInTransaction} to finish (and blocks new ones
     * from starting) so it never observes a mid-write/mid-rollback state. */
    <T> T runInReadOnlyTransaction(Supplier<T> action);
}
