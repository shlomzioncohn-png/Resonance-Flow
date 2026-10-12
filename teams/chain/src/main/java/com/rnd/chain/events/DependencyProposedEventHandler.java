package com.rnd.chain.events;

import com.rnd.chain.dto.DependencyChangeAction;
import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.exception.CrossTenantDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.DependencyHistoryRepository;
import com.rnd.chain.service.TaskEligibilityService;
import com.rnd.mind.event.DependencyProposedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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

    private static final Logger log = LoggerFactory.getLogger(DependencyProposedEventHandler.class);

    private final TaskEligibilityService taskEligibilityService;
    private final ChainEventProcessingTemplate template;
    private final DependencyHistoryRepository historyRepository;

    public DependencyProposedEventHandler(TaskEligibilityService taskEligibilityService,
                                           ChainEventProcessingTemplate template,
                                           DependencyHistoryRepository historyRepository) {
        this.taskEligibilityService = taskEligibilityService;
        this.template = template;
        this.historyRepository = historyRepository;
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

        // confidence (Dependency.getConfidence()) is deliberately never read - confirmed to have
        // no effect on Chain's side, every entry is applied unconditionally.
        List<UUID> dependsOnTaskIds = event.getDependenciesList().stream()
                .map(dependency -> parseUuid(dependency.getDependsOnTaskId(), "dependencies[].depends_on_task_id"))
                .toList();

        template.processClaimed(eventId, "task.dependencies.proposed",
                () -> addDependencies(eventId, taskId, dependsOnTaskIds));
    }

    private void addDependencies(UUID eventId, UUID taskId, List<UUID> dependsOnTaskIds) {
        try {
            taskEligibilityService.addDependencies(taskId, dependsOnTaskIds);
        } catch (TaskNotFoundException e) {
            // Task not found - task_id or one of the dependsOnTaskIds hasn't landed via
            // task.created yet. The team spec guarantees Mind only ever sends final, valid ids,
            // so this is always a timing gap, never a genuinely invalid reference - retryable,
            // see class Javadoc. Which specific id was missing is in e's message/cause.
            throw new ReferencedTaskNotFoundException(
                    "task.dependencies.proposed referenced a task Chain hasn't seen yet (task=" + taskId + ")", e);
        } catch (CrossTenantDependencyException e) {
            // Issue #580: a cross-tenant edge is a structural data problem, not a timing issue -
            // non-retryable, same as a cycle (see the catch below) - but also must stay visible,
            // not silently dropped: this path (unlike the REST addDependency path, see
            // TaskDependencyService) never writes to dependency_history at all today even on
            // success (a pre-existing, documented gap - see chain/CLAUDE.md), so the REJECTED row
            // is recorded directly here rather than assuming some other layer will do it.
            log.warn("task.dependencies.proposed rejected: cross-tenant edge(s) from task {} (event={})",
                    taskId, eventId, e);
            recordRejected(eventId, taskId, dependsOnTaskIds);
            throw new NonRetryableEventException(
                    "task.dependencies.proposed rejected a cross-tenant dependency (task=" + taskId + ")", e);
        } catch (InvalidDependencyException e) {
            // A self-reference or cycle - a structural problem with the data itself, not a timing
            // issue. Retrying won't help, straight to the error topic. Which pair/id is in e's
            // message.
            throw new NonRetryableEventException(
                    "task.dependencies.proposed rejected an invalid dependency (task=" + taskId + ")", e);
        }
    }

    /** Best-effort visibility for a batch rejected before any edge in it was written - one entry
     * per candidate target, since {@link TaskEligibilityService#addDependencies} validates the
     * whole batch before applying any of it (zero partial effect either way). {@code sourceRef}
     * includes the target id, not just {@code eventId}: {@code dependency_history} has a unique
     * index on {@code (source, source_ref)}, so reusing the bare {@code eventId} for every entry
     * in a multi-target batch would make every write after the first a silent no-op. */
    private void recordRejected(UUID eventId, UUID taskId, List<UUID> dependsOnTaskIds) {
        for (UUID dependsOnTaskId : dependsOnTaskIds) {
            historyRepository.record(DependencyHistoryEntry.builder()
                    .id(UUID.randomUUID())
                    .taskId(taskId)
                    .dependsOnTaskId(dependsOnTaskId)
                    .action(DependencyChangeAction.REJECTED)
                    .source(DependencyChangeSource.MIND)
                    .sourceRef(eventId + ":" + dependsOnTaskId)
                    .createdAt(Instant.now())
                    .build());
        }
    }

}
