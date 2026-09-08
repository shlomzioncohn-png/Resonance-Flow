package com.rnd.chain.repository;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * In-memory implementation of {@link TransactionRunner}.
 *
 * <p><b>Best-effort, not ACID:</b> there is no WAL/transaction log behind
 * the in-memory repositories, so this only provides mutual exclusion (a
 * single global lock around {@code action}) - it does NOT provide real
 * rollback. If {@code action} partially updates one repository and then
 * throws before updating another, whatever it already wrote stays
 * written. Callers (see {@code TaskDependencyService}) are responsible for
 * their own compensating action (e.g. deleting a saved edge if recording
 * its history entry fails) inside {@code action} itself.
 *
 * <p><b>Follow-up:</b> once a real DB is chosen, this should be replaced
 * by an implementation backed by {@code @Transactional} (Spring +
 * JDBC/JPA), and the manual compensation in {@code TaskDependencyService}
 * removed, since the DB will perform real rollback.
 */
public class InMemoryTransactionRunner implements TransactionRunner {

    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public <T> T runInTransaction(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
