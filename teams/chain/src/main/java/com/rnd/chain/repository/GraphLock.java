package com.rnd.chain.repository;

import java.util.function.Supplier;

/** Exclusive section over the whole dependency graph, taken before any row lock. */
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
