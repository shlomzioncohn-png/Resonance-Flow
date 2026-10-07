package com.rnd.chain.events;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.MindDependencyPayload;
import com.rnd.chain.dto.MindProposedDependency;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.chain.service.UnresolvedDependencyService;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.InvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class MindDependencyEventHandler
        implements EventHandler<MindDependencyPayload> {

    private static final Logger log =
            LoggerFactory.getLogger(MindDependencyEventHandler.class);

    private static final String EVENT_TYPE =
            "task.dependencies.proposed";

    private final TaskDependencyService taskDependencyService;
    private final TaskRepository taskRepository;
    private final UnresolvedDependencyService unresolvedDependencyService;

    public MindDependencyEventHandler(
            TaskDependencyService taskDependencyService,
            TaskRepository taskRepository,
            UnresolvedDependencyService unresolvedDependencyService) {

        this.taskDependencyService =
                Objects.requireNonNull(
                        taskDependencyService,
                        "taskDependencyService must not be null");

        this.taskRepository =
                Objects.requireNonNull(
                        taskRepository,
                        "taskRepository must not be null");

        this.unresolvedDependencyService =
                Objects.requireNonNull(
                        unresolvedDependencyService,
                        "unresolvedDependencyService must not be null");
    }

    @Override
    public String getSupportedEventType() {
        return EVENT_TYPE;
    }

    @Override
    public Class<MindDependencyPayload> getPayloadType() {
        return MindDependencyPayload.class;
    }

    @Override
    public void handle(
            EventEnvelope<MindDependencyPayload> event) {

        Objects.requireNonNull(
                event,
                "event must not be null");

        MindDependencyPayload payload =
                Objects.requireNonNull(
                        event.getPayload(),
                        "event payload must not be null");

        validate(payload);

        log.info(
                "Processing Mind dependency event [{}] "
                        + "for task [{}] with [{}] dependencies",
                event.getEventId(),
                payload.taskId(),
                payload.dependencies().size());

        Set<UUID> processedDependencyIds =
                new HashSet<>();

        for (MindProposedDependency dependency
                : payload.dependencies()) {

            UUID dependsOnTaskId =
                    dependency.dependsOnTaskId();

            /*
             * Same dependency can theoretically appear more than once
             * inside the same event. Process it only once.
             */
            if (!processedDependencyIds.add(dependsOnTaskId)) {

                log.info(
                        "Skipping duplicate dependency [{} -> {}] "
                                + "inside Mind event [{}]",
                        payload.taskId(),
                        dependsOnTaskId,
                        event.getEventId());

                continue;
            }

            String sourceRef =
                    buildSourceRef(
                            event.getEventId(),
                            payload.taskId(),
                            dependsOnTaskId);

            try {

                taskDependencyService.addDependency(
                        payload.taskId(),
                        dependsOnTaskId,
                        DependencyChangeSource.MIND,
                        sourceRef);

            } catch (DuplicateDependencyException ex) {

                /*
                 * The edge already exists.
                 * This is a benign/idempotent case, not an event failure.
                 */
                log.info(
                        "Dependency [{} -> {}] already exists. "
                                + "Skipping Mind proposal from event [{}]",
                        payload.taskId(),
                        dependsOnTaskId,
                        event.getEventId());
} catch (InvalidDependencyException ex) {

    throw new NonRetryableEventException(
            "Mind proposed an invalid dependency ["
                    + payload.taskId()
                    + " -> "
                    + dependsOnTaskId
                    + "]",
            ex);

} catch (InvalidRequestException ex) {

    throw new NonRetryableEventException(
            "Mind dependency event contains an invalid request for ["
                    + payload.taskId()
                    + " -> "
                    + dependsOnTaskId
                    + "]",
            ex);
            } 
            catch (TaskNotFoundException ex) {

                /*
                 * TaskDependencyService checks both tasks.
                 *
                 * We only treat a missing dependency TARGET as
                 * a recoverable business failure.
                 *
                 * A missing source/canonical task is not handled
                 * as unresolved here.
                 */
                boolean sourceExists =
                        taskRepository
                                .findById(payload.taskId())
                                .isPresent();

                boolean targetExists =
                        taskRepository
                                .findById(dependsOnTaskId)
                                .isPresent();

                if (sourceExists && !targetExists) {

                    log.info(
                            "Dependency target [{}] for task [{}] "
                                    + "does not exist. Saving unresolved "
                                    + "dependency from Mind event [{}]",
                            dependsOnTaskId,
                            payload.taskId(),
                            event.getEventId());

                    unresolvedDependencyService
                            .recordMissingTarget(
                                    payload.taskId(),
                                    dependsOnTaskId,
                                    DependencyChangeSource.MIND,
                                    sourceRef);

                    /*
                     * Partial success:
                     * this dependency failed for a recoverable
                     * business reason, so continue processing
                     * the remaining dependencies.
                     */
                    continue;
                }

                /*
                 * Source task missing, or another unexpected
                 * not-found situation.
                 *
                 * We do not convert it into unresolved.
                 */
                throw ex;
            }
        }

        log.info(
                "Successfully processed Mind dependency event [{}] "
                        + "for task [{}]",
                event.getEventId(),
                payload.taskId());
    }

    private void validate(
            MindDependencyPayload payload) {

        if (payload.taskId() == null) {
            throw new NonRetryableEventException(
                    "Mind dependency event is missing [task_id]");
        }

        List<MindProposedDependency> dependencies =
                payload.dependencies();

        if (dependencies == null) {
            throw new NonRetryableEventException(
                    "Mind dependency event is missing [dependencies]");
        }

        for (MindProposedDependency dependency
                : dependencies) {

            if (dependency == null) {
                throw new NonRetryableEventException(
                        "Mind dependency event contains a null dependency");
            }

            if (dependency.dependsOnTaskId() == null) {
                throw new NonRetryableEventException(
                        "Mind dependency event contains a dependency "
                                + "without [depends_on_task_id]");
            }

            if (dependency.confidence() == null) {
                throw new NonRetryableEventException(
                        "Mind dependency event contains a dependency "
                                + "without [confidence]");
            }

            if (dependency.confidence() < 0.0
                    || dependency.confidence() > 1.0) {

                throw new NonRetryableEventException(
                        "Mind dependency event contains invalid "
                                + "[confidence]: "
                                + dependency.confidence());
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