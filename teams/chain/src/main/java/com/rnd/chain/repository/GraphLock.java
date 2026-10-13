package com.rnd.chain.repository;

import java.util.function.Supplier;

/**
 * Single lock shared by every service that touches dependency edges or reads a DONE status to
 * decide whether an edge counts against blockingCount, taken before any task row lock. Per-task
 * field updates don't need it (TaskRepository#update is an atomic read-modify-write); cycle
 * detection and edge-vs-status decisions do. One key: all graph writes queue, across every project.
 */
public interface GraphLock {

    /** Runs {@code action} while holding the graph lock. */
    <T> T runExclusive(Supplier<T> action);

    default void runExclusive(Runnable action) {
        runExclusive(() -> {
            action.run();
            return null;
        });
    }
}
