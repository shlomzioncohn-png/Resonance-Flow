package com.rnd.chain.repository;

import java.util.function.Supplier;

/** DB-independent transaction boundary seam, so the in-memory impl can later be swapped for a real one. */
public interface TransactionRunner {

    /** Runs {@code action}; best-effort atomicity across repositories. */
    <T> T runInTransaction(Supplier<T> action);
}
