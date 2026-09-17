package com.rnd.chain.events;

import com.google.protobuf.Timestamp;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.TaskRepository;
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
 * Consumes task.updated (Inbox, Protobuf) to feed {@link TaskEligibilityService#onTaskStatusChanged}.
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

    private final TaskRepository taskRepository;
    private final TaskEligibilityService taskEligibilityService;
    private final ChainEventProcessingTemplate template;

    public TaskUpdatedEventHandler(TaskRepository taskRepository,
                                    TaskEligibilityService taskEligibilityService,
                                    ChainEventProcessingTemplate template) {
        this.taskRepository = taskRepository;
        this.taskEligibilityService = taskEligibilityService;
        this.template = template;
    }

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 2000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = ".errors",
            include = ChainTransientProcessingException.class)
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
        Task current = taskRepository.findById(taskId).orElse(null);
        if (current != null && current.getUpdatedAt() != null && !eventUpdatedAt.isAfter(current.getUpdatedAt())) {
            return; // stale or duplicate snapshot - already applied (or superseded), no-op
        }

        try {
            taskEligibilityService.onTaskStatusChanged(taskId, newStatus);
        } catch (IllegalArgumentException e) {
            throw new ReferencedTaskNotFoundException(
                    "task.updated referenced a task Chain hasn't seen yet: " + taskId, e);
        }

        // onTaskStatusChanged preserves updatedAt (see TaskEligibilityService.copyOf) - stamp the
        // event's own updated_at afterwards so the next redelivery/older event can be compared
        // against it. A second, separate atomic update() call: not perfectly one transaction with
        // the status change, but each call is individually atomic per-id, and nothing else in the
        // codebase currently writes updatedAt, so there's no real contention window here.
        taskRepository.update(taskId, task -> Task.builder()
                .id(task.getId())
                .parentTaskId(task.getParentTaskId())
                .ownerId(task.getOwnerId())
                .status(task.getStatus())
                .isReady(task.isReady())
                .blockingCount(task.getBlockingCount())
                .openConstraintCount(task.getOpenConstraintCount())
                .estimatedDurationMin(task.getEstimatedDurationMin())
                .score(task.getScore())
                .createdAt(task.getCreatedAt())
                .updatedAt(eventUpdatedAt)
                .deletedAt(task.getDeletedAt())
                .build());
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
