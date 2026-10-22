package com.rnd.chain.testsupport;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.List;
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

    private static final String PENDING_PUBLISH_KEY =
            "com.rnd.chain.service.SchedulingUpdateService.pendingSchedulingPublishes";

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
        Suspended outer = suspendOuter();
        try {
            T result = action.get();
            TransactionSynchronizationUtils.triggerAfterCommit();
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            return result;
        } finally {
            clearIfActive();
            restoreOuter(outer);
        }
    }

    /** Runs {@code action}, then simulates the transaction it ran "in" rolling back -
     * {@code afterCommit()} is deliberately never called (a rolled-back transaction never
     * commits), only {@code afterCompletion(STATUS_ROLLED_BACK)}. */
    public static void runRolledBack(Runnable action) {
        Suspended outer = suspendOuter();
        try {
            action.run();
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally {
            clearIfActive();
            restoreOuter(outer);
        }
    }

    // A @Transactional test already has synchronization active; park its callbacks so the simulated
    // commit below only fires the ones registered by the action itself.
    private record Suspended(List<TransactionSynchronization> synchronizations, Object pendingPublishes) {
    }

    // Also parks SchedulingUpdateService's per-transaction pending-publish set, which is bound to the thread
    // (not to the synchronization list) and would otherwise make the simulated commit dedup against
    // publishes queued earlier in the outer test transaction.
    private static Suspended suspendOuter() {
        Suspended outer = null;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            Object pending = TransactionSynchronizationManager.hasResource(PENDING_PUBLISH_KEY)
                    ? TransactionSynchronizationManager.unbindResource(PENDING_PUBLISH_KEY) : null;
            outer = new Suspended(List.copyOf(TransactionSynchronizationManager.getSynchronizations()), pending);
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.initSynchronization();
        return outer;
    }

    private static void restoreOuter(Suspended outer) {
        if (outer != null) {
            TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_PUBLISH_KEY);
            if (outer.pendingPublishes() != null) {
                TransactionSynchronizationManager.bindResource(PENDING_PUBLISH_KEY, outer.pendingPublishes());
            }
            TransactionSynchronizationManager.initSynchronization();
            outer.synchronizations().forEach(TransactionSynchronizationManager::registerSynchronization);
        }
    }

    private static void clearIfActive() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
