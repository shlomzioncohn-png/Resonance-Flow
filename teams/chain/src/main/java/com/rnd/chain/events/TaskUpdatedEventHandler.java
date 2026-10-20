package com.rnd.chain.events;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTier;
import com.rnd.chain.exception.TenantReassignmentException;
import com.rnd.chain.service.TaskEligibilityService;
import com.rnd.inbox.event.TaskUpdatedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.rnd.chain.events.EventFields.parseUuid;
import static com.rnd.chain.events.EventFields.toLocalDateTime;

/**
 * Consumes task.updated (Inbox, Protobuf) and applies the incoming task status through
 * {@link TaskEligibilityService#onTaskStatusChangedIfNewer}.
 *
 * If Chain has not seen the task before, the event creates a local Task using the event's
 * status, updatedAt and tenantId, with zero dependency and constraint counters.
 *
 * Inbox's DONE status is not applied here: completion (and releasing dependents) belongs to
 * task.completed alone. A DONE event never changes an existing task's status or updatedAt (only
 * the tenant check still runs); for an unknown task it creates a PENDING task, see
 * {@link TaskEligibilityService#onTaskUpdatedAsDone}. Reopening, CANCELLED and REJECTED are
 * still handled here as before.
 *
 * Idempotency is state-based, last-write-wins: task.updated carries no event id in its body,
 * so the incoming event's updated_at is compared against the task's stored
 * {@link Task#getUpdatedAt()}. For an existing task, only a strictly newer event is applied;
 * duplicate or out-of-order events with an older or equal updated_at are ignored.
 *
 * Atomicity: task creation, the staleness check and the status update are executed inside
 * {@link TaskEligibilityService#onTaskStatusChangedIfNewer}'s graph-lock critical section,
 * preventing concurrent task.updated events for the same task from interleaving incorrectly.
 *
 * task.updated carries no event id, hence state-based idempotency; if it gains one, use
 * {@link ChainEventProcessingTemplate#processClaimed} instead.
 */
@Component
public class TaskUpdatedEventHandler {

    private final TaskEligibilityService taskEligibilityService;
    private final ChainEventProcessingTemplate template;

    public TaskUpdatedEventHandler(TaskEligibilityService taskEligibilityService,
                                    ChainEventProcessingTemplate template) {
        this.taskEligibilityService = taskEligibilityService;
        this.template = template;
    }

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 2000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = ".errors",
            exclude = NonRetryableEventException.class,
            kafkaTemplate = "taskUpdatedEventKafkaTemplate")
    @KafkaListener(
            topics = "${chain.kafka.topics.task-updated:task.updated}",
            groupId = "${chain.kafka.consumer-group-id}",
            containerFactory = "taskUpdatedEventKafkaListenerContainerFactory")
    @Transactional
    public void onMessage(TaskUpdatedEvent event) {
        UUID taskId = parseUuid(event.getTaskId(), "task_id");
        LocalDateTime eventUpdatedAt = toLocalDateTime(event.getUpdatedAt());
        UUID tenantId = parseUuid(event.getTenantId(), "tenant_id");

        // DONE is applied only by task.completed; CANCELLED/REJECTED still release from here.
        if (event.getStatus() == com.rnd.inbox.event.TaskStatus.TASK_STATUS_DONE) {
            template.processUnclaimed(taskId.toString(), "task.updated",
                    () -> apply(taskId, () -> taskEligibilityService.onTaskUpdatedAsDone(
                            taskId, eventUpdatedAt, tenantId)));
            return;
        }

        TaskStatus newStatus = InboxTaskStatusMapper.map(event.getStatus());
        TaskTier tier = InboxTaskTierMapper.map(event);
        template.processUnclaimed(taskId.toString(), "task.updated",
                () -> apply(taskId, () -> taskEligibilityService.onTaskStatusChangedIfNewer(
                        taskId, newStatus, eventUpdatedAt, tenantId, tier)));
    }

    private void apply(UUID taskId, Runnable action) {

    try {
        action.run();

    } catch (TenantReassignmentException e) {
        // Confirmed with Inbox: tenant_id never legitimately changes after creation, so this
        // is always a bug upstream - retrying the same event will never resolve it.
        throw new NonRetryableEventException(
                "task.updated for task " + taskId
                        + " claims a different tenant than stored",
                e);

    } catch (RuntimeException e) {
        // Unexpected failures are still retried as transient failures.
        throw new ChainTransientProcessingException(
                "Failed to apply task.updated for task " + taskId,
                e);
    }
}

}
