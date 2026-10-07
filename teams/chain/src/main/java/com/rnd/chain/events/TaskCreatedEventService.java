package com.rnd.chain.events;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.service.UnresolvedDependencyService;
import com.rnd.inbox.event.TaskCreatedEvent;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class TaskCreatedEventService {

    private final TaskRepository taskRepository;
    private final UnresolvedDependencyService unresolvedDependencyService;

    public TaskCreatedEventService(
            TaskRepository taskRepository,
            UnresolvedDependencyService unresolvedDependencyService) {

        this.taskRepository = taskRepository;
        this.unresolvedDependencyService = unresolvedDependencyService;
    }

   public void handle(TaskCreatedEvent event) {

    UUID taskId = parseTaskId(event.getId());

    boolean alreadyExists =
            taskRepository.findById(taskId).isPresent();

    if (!alreadyExists) {

        Task task = Task.builder()
                .id(taskId)
                .parentTaskId(null)
                .ownerId(null)
                .status(mapStatus(event))
                .isReady(true)
                .blockingCount(0)
                .openConstraintCount(0)
                .estimatedDurationMin(
                        event.hasEffortEstimateMinutes()
                                ? event.getEffortEstimateMinutes()
                                : null)
                .score(null)
                .createdAt(toLocalDateTime(event))
                .updatedAt(toLocalDateTime(event))
                .deletedAt(null)
                .build();

        taskRepository.save(task);
    }

    unresolvedDependencyService
            .resolveWaitingForTask(taskId);
}

    private UUID parseTaskId(String rawTaskId) {

        try {
            return UUID.fromString(rawTaskId);
        } catch (IllegalArgumentException e) {
            throw new NonRetryableEventException(
                    "task.created contains invalid task id: "
                            + rawTaskId,
                    e);
        }
    }

    private TaskStatus mapStatus(TaskCreatedEvent event) {

        if (event.getStatus()
                == com.rnd.inbox.event.TaskStatus.TASK_STATUS_DONE) {

            return TaskStatus.DONE;
        }

        return TaskStatus.ACTIVE;
    }

    private LocalDateTime toLocalDateTime(
            TaskCreatedEvent event) {

        Instant instant =
                Instant.ofEpochSecond(
                        event.getCreatedAt().getSeconds(),
                        event.getCreatedAt().getNanos());

        return LocalDateTime.ofInstant(
                instant,
                ZoneOffset.UTC);
    }
}