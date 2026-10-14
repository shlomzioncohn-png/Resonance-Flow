package com.rnd.chain.events;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.chain.service.UnresolvedDependencyService;
import com.rnd.mind.event.DependencyProposedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static com.rnd.chain.events.EventFields.parseUuid;

@Component
public class DependencyProposedEventHandler {

    private final TaskDependencyService taskDependencyService;
    private final TaskRepository taskRepository;
    private final UnresolvedDependencyService unresolvedDependencyService;
    private final ChainEventProcessingTemplate template;

    public DependencyProposedEventHandler(
            TaskDependencyService taskDependencyService,
            TaskRepository taskRepository,
            UnresolvedDependencyService unresolvedDependencyService,
            ChainEventProcessingTemplate template) {

        this.taskDependencyService = taskDependencyService;
        this.taskRepository = taskRepository;
        this.unresolvedDependencyService = unresolvedDependencyService;
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

        template.processClaimed(
                eventId,
                "task.dependencies.proposed",
                () -> processDependencies(eventId, taskId, event));
    }

    private void processDependencies(
            UUID eventId,
            UUID taskId,
            DependencyProposedEvent event) {

        /*
         * Missing source task is not stored as unresolved.
         * It means Chain has not received the canonical task yet,
         * so allow Kafka retry handling to retry the event.
         */
        if (taskRepository.findById(taskId).isEmpty()) {
            throw new ReferencedTaskNotFoundException(
                "Source task not found for task.dependencies.proposed: " + taskId,
                new IllegalStateException("Source task is not available yet"));
        }

        Set<UUID> processedDependencyIds = new HashSet<>();

        for (var dependency : event.getDependenciesList()) {

            UUID dependsOnTaskId = parseUuid(
                    dependency.getDependsOnTaskId(),
                    "dependencies[].depends_on_task_id");

            /*
             * Same target may appear more than once inside one Mind event.
             */
            if (!processedDependencyIds.add(dependsOnTaskId)) {
                continue;
            }

            // Issue #445: Mind's confidence for this specific edge - feeds
            // PriorityScoreService's B/D terms (a 0.3-confidence blocker moves priority_score
            // less than a 0.9-confidence one).
            double confidence = dependency.getConfidence();

            String sourceRef =
                    buildSourceRef(eventId, taskId, dependsOnTaskId);

            /*
             * Missing target is a recoverable out-of-order case.
             * Store it until task.created arrives.
             */
            if (taskRepository.findById(dependsOnTaskId).isEmpty()) {

                // Issue #445 known gap (deliberate, not fixed here): UnresolvedDependency has no
                // confidence field, so when UnresolvedDependencyService#resolveWaitingForTask
                // later replays this via TaskCreatedEventHandler, the edge is added with the
                // default confidence (1.0), not Mind's real value above. Only affects
                // dependencies proposed before Chain has seen their target task.
                unresolvedDependencyService.recordMissingTarget(
                        taskId,
                        dependsOnTaskId,
                        DependencyChangeSource.MIND,
                        sourceRef);

                continue;
            }

            try {
                taskDependencyService.addDependency(
                        taskId,
                        dependsOnTaskId,
                        DependencyChangeSource.MIND,
                        sourceRef,
                        confidence);

            } catch (DuplicateDependencyException ex) {

                /*
                 * Existing dependency is a benign/idempotent case.
                 */
            } catch (InvalidDependencyException | InvalidRequestException ex) {

                throw new NonRetryableEventException(
                        "Mind proposed an invalid dependency ["
                                + taskId
                                + " -> "
                                + dependsOnTaskId
                                + "]",
                        ex);
            }
        }
    }

    private String buildSourceRef(
            UUID eventId,
            UUID taskId,
            UUID dependsOnTaskId) {

        return eventId
                + ":"
                + taskId
                + ":"
                + dependsOnTaskId;
    }
}
