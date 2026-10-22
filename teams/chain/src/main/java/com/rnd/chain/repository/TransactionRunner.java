package com.rnd.chain.repository;

import java.util.function.Supplier;

/** DB-independent transaction boundary seam,. */
public interface TransactionRunner {

    /** Runs {@code action} atomically. On a real transactional store this is row/constraint-level
     * isolation, not global exclusivity - two calls touching unrelated rows may run concurrently;
     * two calls that touch the same row are still serialized by the store itself. */
    <T> T runInTransaction(Supplier<T> action);

    /** Runs a read-only {@code action}; on a real transactional store, isolated from concurrent
     * writes by the store's own transaction isolation rather than by blocking - it never observes
     * a mid-write/mid-rollback state, but does not itself serialize against unrelated writes. */
    <T> T runInReadOnlyTransaction(Supplier<T> action);
}
