package com.rnd.chain.repository;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Best-effort, not ACID: a single global lock, no real rollback. Callers must
 * do their own compensating action inside {@code action} if a later step fails.
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
