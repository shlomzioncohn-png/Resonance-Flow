package com.rnd.chain.events;

import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.service.TaskEligibilityService;
import com.rnd.mind.event.Dependency;
import com.rnd.mind.event.DependencyProposedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.rnd.chain.events.EventFields.parseUuid;

/**
 * Consumes task.dependencies.proposed (Mind, Protobuf) to feed
 * {@link TaskEligibilityService#addDependencies}.
 *
 * Idempotency: dedupe via {@link ChainEventProcessingTemplate#processClaimed} on event_id, same as
 * any other Mind-originated event - per the team lead's statement that Mind events carry their own
 * event_id. NOT YET CONFIRMED end-to-end though: Mind's actual draft schema (see
 * dependency-proposed-event.proto's header) doesn't show an event_id field - the user is actively
 * working to get Mind to add one. If it turns out not to materialize, this needs a different
 * idempotency strategy - but note {@code TaskEligibilityService.addDependency}/{@code addDependencies}
 * are already naturally idempotent per-edge (re-adding an existing edge is a documented no-op), so
 * even without an event_id, redelivery can't corrupt state - it would only lose the "duplicate
 * detected" observability this event_id-based claim otherwise gives for free.
 *
 * All-or-nothing per event (PR #467 review): every entry in {@code dependencies} is validated
 * against the graph before any of them is applied - see
 * {@link TaskEligibilityService#addDependencies}'s Javadoc for why that's safe to do even though
 * cycle detection is itself graph-state-dependent. So a single bad entry (a cycle, or a target
 * task Chain hasn't seen yet) fails the whole event with zero partial effect, rather than earlier
 * entries silently landing and the rest being dropped.
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
            exclude = NonRetryableEventException.class,
            kafkaTemplate = "dependencyProposedEventKafkaTemplate")
    @KafkaListener(
            topics = "${chain.kafka.topics.dependencies-proposed:task.dependencies.proposed}",
            groupId = "${chain.kafka.consumer-group-id}",
            containerFactory = "dependencyProposedEventKafkaListenerContainerFactory")
    @Transactional
    public void onMessage(DependencyProposedEvent event) {
        UUID eventId = parseUuid(event.getEventId(), "event_id");
        UUID taskId = parseUuid(event.getTaskId(), "task_id");

        // Issue #445: confidence now feeds PriorityScoreService's B/D terms (a 0.3-confidence
        // blocker moves priority_score less than a 0.9-confidence one) - no longer ignored the
        // way the class Javadoc above and the .proto file used to document.
        Map<UUID, Double> dependsOnTaskIdsWithConfidence = new LinkedHashMap<>();
        for (Dependency dependency : event.getDependenciesList()) {
            UUID dependsOnTaskId = parseUuid(dependency.getDependsOnTaskId(), "dependencies[].depends_on_task_id");
            dependsOnTaskIdsWithConfidence.put(dependsOnTaskId, (double) dependency.getConfidence());
        }

        template.processClaimed(eventId, "task.dependencies.proposed",
                () -> addDependencies(taskId, dependsOnTaskIdsWithConfidence));
    }

    private void addDependencies(UUID taskId, Map<UUID, Double> dependsOnTaskIdsWithConfidence) {
        try {
            taskEligibilityService.addDependencies(taskId, dependsOnTaskIdsWithConfidence);
        } catch (TaskNotFoundException e) {
            // Task not found - task_id or one of the dependsOnTaskIds hasn't landed via
            // task.created yet. The team spec guarantees Mind only ever sends final, valid ids,
            // so this is always a timing gap, never a genuinely invalid reference - retryable,
            // see class Javadoc. Which specific id was missing is in e's message/cause.
            throw new ReferencedTaskNotFoundException(
                    "task.dependencies.proposed referenced a task Chain hasn't seen yet (task=" + taskId + ")", e);
        } catch (InvalidDependencyException e) {
            // Either a cycle, or (issue #445) a confidence outside [0, 1] - both are structural
            // problems with the data itself, not a timing issue. Retrying won't help, straight to
            // the error topic. Which pair/value is in e's message.
            throw new NonRetryableEventException(
                    "task.dependencies.proposed was rejected (task=" + taskId + ")", e);
        }
    }

}
