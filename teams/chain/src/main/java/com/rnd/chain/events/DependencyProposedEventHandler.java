package com.rnd.chain.events;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.service.TaskCounterUpdater;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.mind.event.DependencyProposedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.rnd.chain.events.EventFields.parseUuid;

/**
 * Consumes task.dependencies.proposed. A task referenced by the event that Chain doesn't know yet
 * is created as a PENDING placeholder in the event's tenant, so the edge is recorded right away.
 * Cycles, invalid dependencies and cross-tenant edges go straight to .errors without retry.
 */
@Component
public class DependencyProposedEventHandler {

    private final TaskDependencyService taskDependencyService;
    private final TaskCounterUpdater taskCounterUpdater;
    private final TaskReadinessRepository taskReadinessRepository;
    private final ChainEventProcessingTemplate template;

    public DependencyProposedEventHandler(
            TaskDependencyService taskDependencyService,
            TaskCounterUpdater taskCounterUpdater,
            TaskReadinessRepository taskReadinessRepository,
            ChainEventProcessingTemplate template) {

        this.taskDependencyService = taskDependencyService;
        this.taskCounterUpdater = taskCounterUpdater;
        this.taskReadinessRepository = taskReadinessRepository;
        this.template = template;
    }

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 2000),
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
        UUID tenantId = parseUuid(event.getTenantId(), "tenant_id");

        template.processClaimed(
                eventId,
                "task.dependencies.proposed",
                () -> processDependencies(eventId, taskId, tenantId, event));
    }

    private void processDependencies(
            UUID eventId,
            UUID taskId,
            UUID tenantId,
            DependencyProposedEvent event) {

        // Guarantees a row even for an empty dependency list, so the after-commit publish finds it.
        taskCounterUpdater.insertPlaceholderIfAbsent(taskId, tenantId);

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

            try {
                taskDependencyService.addDependencyCreatingMissing(
                        taskId,
                        dependsOnTaskId,
                        DependencyChangeSource.MIND,
                        sourceRef,
                        confidence,
                        tenantId);

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

        // Inbox holds has_dependencies tasks as unavailable until Chain says otherwise, so a task that is
        // already ready (no edges, or all blockers done) never gets a readiness transition to publish.
        TaskReadinessSnapshot readiness = taskReadinessRepository.findByIds(List.of(taskId)).get(taskId);
        if (readiness != null && readiness.isReady()) {
            taskCounterUpdater.requestPublishAfterCommit(taskId);
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
