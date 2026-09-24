package com.rnd.chain.events;

import com.google.protobuf.Timestamp;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.service.TaskEligibilityService;
import com.rnd.inbox.event.TaskUpdatedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Consumes task.updated (Inbox, Protobuf) to feed {@link TaskEligibilityService#onTaskStatusChangedIfNewer}.
 *
 * Idempotency - state-based, last-write-wins (confirmed by the team lead): task.updated carries
 * no event id in its body, so there is no claim table to dedupe against here (contrast with
 * DependencyProposedEventHandler, which does have one). Instead, the incoming event's updated_at
 * is compared against the task's own stored {@link Task#getUpdatedAt()} - applied only if strictly
 * newer, so an out-of-order or duplicate redelivery of an older/equal snapshot is a safe no-op.
 * Nothing in the current codebase ever set Task.updatedAt before this handler existed (verified:
 * every TaskEligibilityService mutator preserves it unchanged via copyOf), so repurposing it as
 * "the updated_at last received from Inbox" doesn't collide with any other writer.
 *
 * Atomicity (PR #467 review): the staleness check, the status write, and the {@code updatedAt}
 * stamp all happen inside {@link TaskEligibilityService#onTaskStatusChangedIfNewer}'s single
 * {@code dependencyGraphLock} critical section - not three separate calls from here - so a
 * redelivered/out-of-order event racing a newer one for the same task can't interleave between
 * them. See that method's Javadoc for how this is meant to carry over once persistence is a real
 * transactional store.
 *
 * OPEN DISCREPANCY (see the implementation plan, section 7): task-created-event.proto's header
 * comment states Inbox's standing convention is that every event carries an event_id as a Kafka
 * header (not a body field) "same as today" - which would suggest task.updated has one too,
 * contradicting the team lead's explicit "task.updated has no event id" answer. This handler
 * follows the team lead's answer (state-based, no claim) since it's the more specific, most
 * recent instruction for this exact topic - but that discrepancy is unresolved, not silently
 * decided, and is called out explicitly to the team in a separate message. If it turns out
 * task.updated does carry a header event id, this handler should switch to
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
            include = ChainTransientProcessingException.class,
            kafkaTemplate = "taskUpdatedEventKafkaTemplate")
    @KafkaListener(
            topics = "${chain.kafka.topics.task-updated:task.updated}",
            groupId = "${chain.kafka.consumer-group-id}",
            containerFactory = "taskUpdatedEventKafkaListenerContainerFactory")
    @Transactional
    public void onMessage(TaskUpdatedEvent event) {
        UUID taskId = parseUuid(event.getTaskId(), "task_id");
        TaskStatus newStatus = InboxTaskStatusMapper.map(event.getStatus());
        LocalDateTime eventUpdatedAt = toLocalDateTime(event.getUpdatedAt());

        template.processUnclaimed(taskId.toString(), "task.updated", () -> apply(taskId, newStatus, eventUpdatedAt));
    }

    private void apply(UUID taskId, TaskStatus newStatus, LocalDateTime eventUpdatedAt) {
        try {
            taskEligibilityService.onTaskStatusChangedIfNewer(taskId, newStatus, eventUpdatedAt);
        } catch (IllegalArgumentException e) {
            throw new ReferencedTaskNotFoundException(
                    "task.updated referenced a task Chain hasn't seen yet: " + taskId, e);
        } catch (RuntimeException e) {
            // Same reasoning as TaskCreatedEventHandler#persist: an unexpected failure here (e.g.
            // a bug in the repository) must not fall outside @RetryableTopic's allow-list, or it
            // skips retry entirely and lands straight on .errors looking like nothing happened.
            throw new ChainTransientProcessingException(
                    "Failed to apply task.updated for task " + taskId, e);
        }
    }

    private static UUID parseUuid(String raw, String fieldName) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new NonRetryableEventException("Invalid " + fieldName + " (not a UUID): " + raw, e);
        }
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return LocalDateTime.ofEpochSecond(ts.getSeconds(), ts.getNanos(), ZoneOffset.UTC);
    }
}
