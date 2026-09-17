package com.rnd.chain.events;

import com.rnd.chain.service.TaskEligibilityService;
import com.rnd.mind.event.DependencyProposedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consumes task.dependencies.proposed (Mind, Protobuf) to feed
 * {@link TaskEligibilityService#addDependency}.
 *
 * Idempotency: this topic's events carry their own event_id (Mind's own, per the team lead) -
 * dedupe via {@link ChainEventProcessingTemplate#processClaimed}, same as any other Mind-originated
 * event.
 *
 * Out-of-order handling - INTERIM approach, not the target design:
 * The implementation plan's real design is an upsert-placeholder: if a dependency references a
 * task Chain hasn't seen yet, upsert a minimal placeholder row immediately instead of retrying,
 * so a normal race with task.created never becomes an error at all. That requires
 * TaskRepository#save() to support an upsert of a placeholder row and a way to flag it as such
 * (an is_placeholder column or equivalent) - neither exists yet on the current {@code Task} DTO
 * (owned by issue #377, not this one: {@code ownerId} is a required, non-null constructor
 * argument, so a placeholder Task can't even be constructed today).
 * Until #377 adds that support, this handler falls back to the simpler, previously-considered
 * alternative from the same design discussion: catch the "task not found" case and make it
 * retryable with a longer backoff (5 attempts, 5s apart - see the retry-topic config below) so a
 * normal task.created-vs-task.dependencies.proposed race usually resolves itself within the retry
 * window instead of failing outright. Revisit and replace with the upsert-placeholder pattern once
 * #377 lands - see ReferencedTaskNotFoundException's own TODO.
 */
@Component
public class DependencyProposedEventHandler {

    private final TaskEligibilityService taskEligibilityService;
    private final ChainEventProcessingTemplate template;

    public DependencyProposedEventHandler(TaskEligibilityService taskEligibilityService,
                                           ChainEventProcessingTemplate template) {
        this.taskEligibilityService = taskEligibilityService;
        this.template = template;
    }

    @RetryableTopic(
            attempts = "5",
            backoff = @Backoff(delay = 5000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = ".errors",
            include = ChainTransientProcessingException.class)
    @KafkaListener(
            topics = "${chain.kafka.topics.dependencies-proposed:task.dependencies.proposed}",
            groupId = "${chain.kafka.consumer-group-id}",
            containerFactory = "dependencyProposedEventKafkaListenerContainerFactory")
    @Transactional
    public void onMessage(DependencyProposedEvent event) {
        UUID eventId = parseUuid(event.getEventId(), "event_id");
        UUID taskId = parseUuid(event.getTaskId(), "task_id");

        template.processClaimed(eventId, "task.dependencies.proposed", () -> {
            for (String rawDependsOnTaskId : event.getDependencyTaskIdsList()) {
                UUID dependsOnTaskId = parseUuid(rawDependsOnTaskId, "dependency_task_ids");
                addDependency(taskId, dependsOnTaskId);
            }
        });
    }

    private void addDependency(UUID taskId, UUID dependsOnTaskId) {
        try {
            taskEligibilityService.addDependency(taskId, dependsOnTaskId);
        } catch (IllegalArgumentException e) {
            // Task not found - task_id or dependsOnTaskId hasn't landed via task.created yet.
            // The team spec guarantees Mind only ever sends final, valid ids, so this is always a
            // timing gap, never a genuinely invalid reference - retryable, see class Javadoc.
            throw new ReferencedTaskNotFoundException(
                    "task.dependencies.proposed referenced a task Chain hasn't seen yet "
                            + "(task=" + taskId + ", dependsOn=" + dependsOnTaskId + ")", e);
        } catch (IllegalStateException e) {
            // Cycle detected - a structural problem with the data itself, not a timing issue.
            // Retrying won't help, straight to the error topic.
            throw new NonRetryableEventException(
                    "task.dependencies.proposed would create a cycle (task=" + taskId
                            + ", dependsOn=" + dependsOnTaskId + ")", e);
        }
    }

    private static UUID parseUuid(String raw, String fieldName) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new NonRetryableEventException("Invalid " + fieldName + " (not a UUID): " + raw, e);
        }
    }
}
