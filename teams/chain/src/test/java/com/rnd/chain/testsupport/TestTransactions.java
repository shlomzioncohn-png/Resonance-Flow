package com.rnd.chain.testsupport;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.function.Supplier;

/**
 * Issue #445 Phase 3: simulates a transaction's commit/rollback for a plain unit test that
 * constructs {@code TaskEligibilityService} directly (no Spring context, no real
 * {@code PlatformTransactionManager}) - {@code TransactionSynchronizationManager} is a plain
 * ThreadLocal-based utility class, usable standalone like this; {@link TransactionSynchronizationUtils}'s
 * {@code trigger*} methods are the same ones {@code AbstractPlatformTransactionManager} itself
 * calls at commit/rollback, so this exercises the real callback-invocation mechanics, not a
 * reimplementation of them.
 *
 * <p>Needed because {@code TaskEligibilityService.requestPublishAfterCommit} only queues a
 * publish when {@link TransactionSynchronizationManager#isSynchronizationActive()} is true
 * (the locked "fallbackExecution=false" decision - see that method's Javadoc) - a test that calls
 * a service method directly, with no active transaction, would otherwise see every publish
 * silently dropped.
 */
public final class TestTransactions {

    private TestTransactions() {
    }

    /** Runs {@code action}, then simulates the transaction it ran "in" committing - any
     * {@code TransactionSynchronization} registered during {@code action} has its
     * {@code afterCommit()} then {@code afterCompletion(STATUS_COMMITTED)} invoked, same as a
     * real transaction manager would do. */
    public static void runCommitted(Runnable action) {
        runCommitted(() -> {
            action.run();
            return null;
        });
    }

    /** Same as {@link #runCommitted(Runnable)}, for an {@code action} that returns a value. */
    public static <T> T runCommitted(Supplier<T> action) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            T result = action.get();
            TransactionSynchronizationUtils.triggerAfterCommit();
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            return result;
        } finally {
            clearIfActive();
        }
    }

    /** Runs {@code action}, then simulates the transaction it ran "in" rolling back -
     * {@code afterCommit()} is deliberately never called (a rolled-back transaction never
     * commits), only {@code afterCompletion(STATUS_ROLLED_BACK)}. */
    public static void runRolledBack(Runnable action) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            action.run();
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally {
            clearIfActive();
        }
    }

    private static void clearIfActive() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
