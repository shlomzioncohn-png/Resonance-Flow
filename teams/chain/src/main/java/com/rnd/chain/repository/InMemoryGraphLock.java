package com.rnd.chain.repository;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Single-JVM stand-in for {@link PostgresGraphLock}: released when the action returns (no commit to wait for). */
public class InMemoryGraphLock implements GraphLock {

    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public <T> T runExclusive(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
