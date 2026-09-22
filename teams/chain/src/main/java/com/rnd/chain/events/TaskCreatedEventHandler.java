package com.rnd.chain.events;

import com.google.protobuf.Timestamp;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.inbox.event.TaskCreatedEvent;
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
 * Consumes task.created (Inbox, Protobuf) - the event that tells Chain a task exists at all.
 * Without this, Chain has no task row to attach a dependency to (see
 * DependencyProposedEventHandler).
 *
 * Idempotency: no event-id-based claim needed for this topic. The write is a genuine upsert keyed
 * on the task's own id (redelivering the same task.created event reapplies the same data, a safe
 * no-op by construction) - a third idempotency shape alongside the two
 * ChainEventProcessingTemplate documents (event-id claim, and state-based/last-write-wins),
 * simpler than either since there's nothing to claim or compare.
 *
 * Merge-not-overwrite on redelivery: if the task row already exists (a plain redelivery, or -
 * once #377 adds placeholder-row support - a placeholder created by an out-of-order
 * task.dependencies.proposed event landing first) this handler must not reset blockingCount /
 * openConstraintCount / isReady back to their creation defaults, since those may already reflect
 * real dependency state Chain computed after the row was first created. It only overwrites the
 * Inbox-owned fields (ownerId, status) and falls back to a fresh insert when the row is genuinely
 * new.
 */
@Component
public class TaskCreatedEventHandler {

    private final TaskRepository taskRepository;
    private final ChainEventProcessingTemplate template;

    public TaskCreatedEventHandler(TaskRepository taskRepository, ChainEventProcessingTemplate template) {
        this.taskRepository = taskRepository;
        this.template = template;
    }

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 2000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = ".errors",
            include = ChainTransientProcessingException.class,
            kafkaTemplate = "taskCreatedEventKafkaTemplate")
    @KafkaListener(
            topics = "${chain.kafka.topics.task-created:task.created}",
            groupId = "${chain.kafka.consumer-group-id}",
            containerFactory = "taskCreatedEventKafkaListenerContainerFactory")
    @Transactional
    public void onMessage(TaskCreatedEvent event) {
        UUID taskId = parseUuid(event.getId(), "id");
        TaskStatus status = InboxTaskStatusMapper.map(event.getStatus());
        LocalDateTime createdAt = toLocalDateTime(event.getCreatedAt());

        template.processUnclaimed(taskId.toString(), "task.created", () -> persist(taskId, event, status, createdAt));
    }

    private void persist(UUID taskId, TaskCreatedEvent event, TaskStatus status, LocalDateTime createdAt) {
        try {
            boolean existed = taskRepository.update(taskId, existing -> Task.builder()
                    .id(existing.getId())
                    .parentTaskId(existing.getParentTaskId())
                    .ownerId(event.getOwnerId())
                    .status(status)
                    .isReady(existing.isReady())
                    .blockingCount(existing.getBlockingCount())
                    .openConstraintCount(existing.getOpenConstraintCount())
                    .estimatedDurationMin(existing.getEstimatedDurationMin())
                    .score(existing.getScore())
                    .createdAt(existing.getCreatedAt())
                    .updatedAt(existing.getUpdatedAt())
                    .deletedAt(existing.getDeletedAt())
                    .build()).isPresent();

            if (!existed) {
                taskRepository.save(Task.builder()
                        .id(taskId)
                        .ownerId(event.getOwnerId())
                        .status(status)
                        .isReady(true)
                        .blockingCount(0)
                        .openConstraintCount(0)
                        .createdAt(createdAt)
                        .updatedAt(createdAt)
                        .build());
            }
        } catch (RuntimeException e) {
            throw new ChainTransientProcessingException(
                    "Failed to persist task " + taskId + " from task.created", e);
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
