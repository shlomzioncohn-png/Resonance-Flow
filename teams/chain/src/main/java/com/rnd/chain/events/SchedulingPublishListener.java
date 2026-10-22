package com.rnd.chain.events;

import com.rnd.chain.dto.Task;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.service.SchedulingUpdateService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Issue #445: the thing that actually calls {@link SchedulingUpdateService} once a transaction
 * that changed a task's {@code priority_score}/{@code is_available} has committed -
 * {@code TaskEligibilityService} calls {@link #onCommit} from a
 * {@code TransactionSynchronization.afterCommit()} callback it registers itself (see that class),
 * never synchronously. Re-reads the task fresh rather than taking a caller-supplied snapshot,
 * since the task may have changed again later in the same transaction after the commit callback
 * was queued.
 *
 * <p><b>Retry</b> (locked decision): 3 attempts, exponential backoff starting at 200ms (200ms,
 * then 400ms). Runs synchronously on the committing thread - same trade-off every
 * {@code @RetryableTopic} consumer in this module already makes (bounded, documented blocking
 * instead of an async retry queue). A persistent failure after all 3 attempts is logged at ERROR
 * and the message is lost - a full fix (outbox, DLT, etc.) is tracked in a separate issue, not
 * this one.
 */
@Component
public class SchedulingPublishListener {

    private static final Logger log = LoggerFactory.getLogger(SchedulingPublishListener.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MILLIS = 200;

    /** Real sleep, used by the public (production) constructor. */
    private static final Sleeper REAL_SLEEPER = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis);
    }

    private final TaskRepository taskRepository;
    private final SchedulingUpdateService schedulingUpdateService;
    private final Sleeper sleeper;

    @Autowired
    public SchedulingPublishListener(TaskRepository taskRepository, SchedulingUpdateService schedulingUpdateService) {
        this(taskRepository, schedulingUpdateService, REAL_SLEEPER);
    }

    /** Test-only seam: a fake {@link Sleeper} so retry/backoff tests don't actually block. */
    SchedulingPublishListener(TaskRepository taskRepository, SchedulingUpdateService schedulingUpdateService,
                               Sleeper sleeper) {
        this.taskRepository = taskRepository;
        this.schedulingUpdateService = schedulingUpdateService;
        this.sleeper = sleeper;
    }

    /**
     * Called after commit for one taskId that changed during the just-committed transaction
     * (deduplicated by the caller - see {@code TaskEligibilityService}, so this runs at most once
     * per taskId per transaction regardless of how many times that task was touched in it).
     * {@code isAvailable} is the task_readiness value captured in the originating transaction.
     */
    public void onCommit(UUID taskId, boolean isAvailable) {
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            // Soft-deleted or genuinely gone between commit and this callback running - nothing
            // meaningful to publish. Not an error: findById's own soft-delete filtering already
            // means recomputePriorityScoreAndPublish wouldn't have queued this for a task that
            // was soft-deleted when it ran, but another statement could have deleted it since.
            log.warn("Task {} not found when its scheduling update was due to publish after commit - "
                    + "nothing to publish", taskId);
            return;
        }
        publishWithRetry(task, isAvailable);
    }

    private void publishWithRetry(Task task, boolean isAvailable) {
        long backoff = INITIAL_BACKOFF_MILLIS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                schedulingUpdateService.publishSchedulingUpdate(task, isAvailable);
                return;
            } catch (RuntimeException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("Persistently failed to publish task.scheduling.updated for task {} "
                                    + "after {} attempts - the message is lost. A full fix (outbox/DLT) is "
                                    + "tracked separately, not in #445.",
                            task.getId(), MAX_ATTEMPTS, e);
                    return;
                }
                log.warn("Attempt {}/{} to publish task.scheduling.updated for task {} failed, "
                        + "retrying in {}ms", attempt, MAX_ATTEMPTS, task.getId(), backoff, e);
                sleeper.sleep(backoff);
                backoff *= 2;
            }
        }
    }
}
