package com.rnd.chain.repository;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Best-effort, not ACID: a single global read-write lock, no real rollback. Callers must
 * do their own compensating action inside {@code action} if a later step fails. Reads run
 * concurrently with each other; writes are exclusive against both reads and other writes.
 */
public class InMemoryTransactionRunner implements TransactionRunner {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public <T> T runInTransaction(Supplier<T> action) {
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public <T> T runInReadOnlyTransaction(Supplier<T> action) {
        lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }
}
