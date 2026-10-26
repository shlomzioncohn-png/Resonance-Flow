package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.events.SchedulingUpdatePublisher;
import com.rnd.chain.events.TaskSchedulingUpdatedEvent;
import com.rnd.chain.repository.TaskReadinessRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SchedulingUpdateService {

    private static final Logger log =
            LoggerFactory.getLogger(SchedulingUpdateService.class);

    /**
     * TransactionSynchronizationManager resource key for the per-transaction
     * map of taskId to the is_available value (from task_readiness) captured when the publish was
     * last requested.
     */
    private static final String PENDING_PUBLISH_RESOURCE_KEY =
            SchedulingUpdateService.class.getName()
                    + ".pendingSchedulingPublishes";

    private final SchedulingUpdatePublisher schedulingUpdatePublisher;
    private final TaskReadinessRepository taskReadinessRepository;

    public SchedulingUpdateService(
            SchedulingUpdatePublisher schedulingUpdatePublisher,
            TaskReadinessRepository taskReadinessRepository) {

        this.schedulingUpdatePublisher = schedulingUpdatePublisher;
        this.taskReadinessRepository = taskReadinessRepository;
    }

    /**
     * @param isAvailable the task_readiness value captured inside the originating transaction
     */
    public void publishSchedulingUpdate(Task task, boolean isAvailable) {

        // Issue #445: priorityScore is Chain's feasibility-adjusted value (PriorityScoreService),
        // not Mind's raw score - task.getScore() was a stand-in used before priorityScore existed.
        TaskSchedulingUpdatedEvent event =
                new TaskSchedulingUpdatedEvent(
                        UUID.randomUUID(),
                        task.getId(),
                        isAvailable,
                        task.getPriorityScore()
                );

        schedulingUpdatePublisher.publish(event);
    }

    /**
     * Queues one scheduling update per taskId per transaction. Reads is_available from
     * task_readiness now, inside the transaction, and hands it to the after-commit callback; a
     * repeat request for the same taskId refreshes that value. Throws if the view has no row.
     *
     * Publishing occurs only after a successful commit, through the supplied
     * listener (passed in rather than injected because the listener itself
     * depends on this service).
     */
    public void requestPublishAfterCommit(
            UUID taskId,
            SchedulingPublishListener schedulingPublishListener) {

        if (!TransactionSynchronizationManager
                .isSynchronizationActive()) {

            log.error(
                    "requestPublishAfterCommit({}) called with no active "
                            + "transaction - publish dropped",
                    taskId);

            return;
        }

        boolean isAvailable =
                requireReadinessSnapshot(taskId).isReady();

        @SuppressWarnings("unchecked")
        Map<UUID, Boolean> pending =
                (Map<UUID, Boolean>)
                        TransactionSynchronizationManager
                                .getResource(
                                        PENDING_PUBLISH_RESOURCE_KEY);

        if (pending == null) {

            pending =
                    new LinkedHashMap<>();

            TransactionSynchronizationManager
                    .bindResource(
                            PENDING_PUBLISH_RESOURCE_KEY,
                            pending);
        }

        boolean alreadyQueued =
                pending.put(taskId, isAvailable) != null;

        if (alreadyQueued) {
            return;
        }

        if (pending.size() == 1) {

            TransactionSynchronizationManager
                    .registerSynchronization(
                            new TransactionSynchronization() {

                                @Override
                                public void afterCommit() {

                                    @SuppressWarnings("unchecked")
                                    Map<UUID, Boolean> toPublish =
                                            (Map<UUID, Boolean>)
                                                    TransactionSynchronizationManager
                                                            .getResource(
                                                                    PENDING_PUBLISH_RESOURCE_KEY);

                                    toPublish.forEach(
                                            schedulingPublishListener::onCommit);
                                }

                                @Override
                                public void afterCompletion(
                                        int status) {

                                    TransactionSynchronizationManager
                                            .unbindResourceIfPossible(
                                                    PENDING_PUBLISH_RESOURCE_KEY);
                                }
                            });
        }
    }

    private TaskReadinessSnapshot requireReadinessSnapshot(
            UUID taskId) {

        TaskReadinessSnapshot snapshot =
                taskReadinessRepository
                        .findByIds(List.of(taskId))
                        .get(taskId);

        if (snapshot == null) {

            throw new IllegalStateException(
                    "No task_readiness row found for task "
                            + taskId);
        }

        return snapshot;
    }
}
