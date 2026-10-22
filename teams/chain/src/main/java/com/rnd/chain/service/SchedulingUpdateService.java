package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.events.SchedulingUpdatePublisher;
import com.rnd.chain.events.TaskSchedulingUpdatedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class SchedulingUpdateService {

    private static final Logger log =
            LoggerFactory.getLogger(SchedulingUpdateService.class);

    /**
     * TransactionSynchronizationManager resource key for the per-transaction
     * set of taskIds queued to publish at commit.
     */
    private static final String PENDING_PUBLISH_RESOURCE_KEY =
            SchedulingUpdateService.class.getName()
                    + ".pendingSchedulingPublishes";

    private final SchedulingUpdatePublisher schedulingUpdatePublisher;

    public SchedulingUpdateService(
            SchedulingUpdatePublisher schedulingUpdatePublisher) {

        this.schedulingUpdatePublisher = schedulingUpdatePublisher;
    }

    public void publishSchedulingUpdate(Task task) {

        // Issue #445: priorityScore is Chain's feasibility-adjusted value (PriorityScoreService),
        // not Mind's raw score - task.getScore() was a stand-in used before priorityScore existed.
        TaskSchedulingUpdatedEvent event =
                new TaskSchedulingUpdatedEvent(
                        UUID.randomUUID(),
                        task.getId(),
                        task.isReady(),
                        task.getPriorityScore()
                );

        schedulingUpdatePublisher.publish(event);
    }

    /**
     * Queues one scheduling update per taskId per transaction.
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

        @SuppressWarnings("unchecked")
        Set<UUID> pending =
                (Set<UUID>)
                        TransactionSynchronizationManager
                                .getResource(
                                        PENDING_PUBLISH_RESOURCE_KEY);

        if (pending == null) {

            pending =
                    new LinkedHashSet<>();

            TransactionSynchronizationManager
                    .bindResource(
                            PENDING_PUBLISH_RESOURCE_KEY,
                            pending);
        }

        if (!pending.add(taskId)) {
            return;
        }

        if (pending.size() == 1) {

            TransactionSynchronizationManager
                    .registerSynchronization(
                            new TransactionSynchronization() {

                                @Override
                                public void afterCommit() {

                                    @SuppressWarnings("unchecked")
                                    Set<UUID> idsToPublish =
                                            (Set<UUID>)
                                                    TransactionSynchronizationManager
                                                            .getResource(
                                                                    PENDING_PUBLISH_RESOURCE_KEY);

                                    for (UUID id :
                                            idsToPublish) {

                                        schedulingPublishListener
                                                .onCommit(id);
                                    }
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
}
