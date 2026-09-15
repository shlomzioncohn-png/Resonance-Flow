package com.rnd.inbox.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.outbox.OutboxEvent;
import com.rnd.inbox.outbox.OutboxEventRepository;
import com.rnd.inbox.scheduling.SchedulingEventDto;
import com.rnd.inbox.shared.event.EventType;
import com.rnd.inbox.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class TaskService {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final TaskRepository taskRepository;
    private final AuditService auditService;
    private final OutboxEventRepository outboxEventRepository;
    private final AssignmentRepository assignmentRepository;
    private final TaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository,
                       AuditService auditService,
                       OutboxEventRepository outboxEventRepository,
                       AssignmentRepository assignmentRepository,
                       TaskMapper taskMapper,
                       ObjectMapper objectMapper,
                       Clock clock) {
        this.taskRepository = taskRepository;
        this.auditService = auditService;
        this.outboxEventRepository = outboxEventRepository;
        this.assignmentRepository = assignmentRepository;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public Task createTask(Task incoming, UUID correlationId) {
        return createTask(incoming, List.of(), correlationId);
    }

    @Transactional
    public Task createTask(Task incoming, List<Assignment> assignments, UUID correlationId) {
        Optional<Task> existing = findExisting(incoming);
        if (existing.isPresent()) {
            return existing.get();
        }

        Task saved = insertTask(incoming);

        for (Assignment assignment : assignments) {
            assignment.setTask(saved);
            assignmentRepository.save(assignment);
        }

        outboxEventRepository.save(buildOutboxEvent(EventType.TASK_CREATED, TaskCreatedPayload.from(saved), saved, correlationId));
        auditService.createAuditLog("TASK_CREATED", "Task", saved.getId(), saved.getTenant(), saved.getCreatedBy(),
                auditSnapshot(saved), correlationId);
        return saved;
    }

    @Transactional
    public Task updateTask(UUID taskId, Task changes, UUID correlationId) {
        Task existing = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        taskMapper.applyUpdate(existing, changes);

        return updateTask(existing, correlationId);
    }

    @Transactional
    public Task updateTask(Task task, UUID correlationId) {
        Task saved = taskRepository.save(task);

        outboxEventRepository.save(buildOutboxEvent(EventType.TASK_UPDATED, TaskUpdatedPayload.from(saved), saved, correlationId));
        auditService.createAuditLog("TASK_UPDATED", "Task", saved.getId(), saved.getTenant(),
                auditSnapshot(saved), correlationId);
        return saved;
    }

    @Transactional
    public Task completeTask(UUID taskId, User completedBy, UUID correlationId) {
        Task existing = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        return completeTask(existing, completedBy, correlationId);
    }

    @Transactional
    public Task completeTask(Task task, User completedBy, UUID correlationId) {
        task.setStatus(TaskStatus.DONE);
        task.setCompletedAt(Instant.now(clock));
        Task saved = taskRepository.save(task);

        outboxEventRepository.save(buildOutboxEvent(EventType.TASK_COMPLETED, TaskCompletedPayload.from(saved), saved, correlationId));
        auditService.createAuditLog("TASK_COMPLETED", "Task", saved.getId(), saved.getTenant(), completedBy,
                auditSnapshot(saved), correlationId);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<Task> findExisting(Task incoming) {
        return taskRepository.findByTenant_IdAndSourceTypeAndSourceRef(
                incoming.getTenant().getId(), incoming.getSourceType(), incoming.getSourceRef());
    }

    public Task applySchedulingUpdate(SchedulingEventDto event) {
        return applySchedulingUpdate(
                UUID.fromString(event.getTaskId()),
                event.getAvailable(),
                event.getPriorityScore() != null ? event.getPriorityScore().floatValue() : null,
                event.getEventId() != null ? UUID.fromString(event.getEventId()) : UUID.randomUUID());
    }

    @Transactional
    public Task applySchedulingUpdate(UUID taskId, boolean available, Float priorityScore, UUID correlationId) {
        Task task = taskRepository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));

        task.setAvailable(available);
        if (priorityScore != null) {
            task.setPriorityScore(priorityScore);
        }
        task.setUpdatedAt(Instant.now(clock));

        Task saved = taskRepository.saveAndFlush(task);

        outboxEventRepository.save(buildOutboxEvent(EventType.TASK_UPDATED, TaskUpdatedPayload.from(saved), saved, correlationId));
        auditService.createAuditLog("TASK_SCHEDULING_UPDATED", "Task", saved.getId(), saved.getTenant(),
                schedulingSnapshot(saved), correlationId);
        return saved;
    }

    private Task insertTask(Task incoming) {
        return taskRepository.saveAndFlush(incoming);
    }

    private OutboxEvent buildOutboxEvent(EventType eventType, Object payload, Task saved, UUID correlationId) {
        Map<String, Object> payloadMap = objectMapper.convertValue(payload, PAYLOAD_TYPE);

        return OutboxEvent.pending(
                eventType,
                saved.getId(),
                saved.getId().toString(),
                payloadMap,
                correlationId,
                Instant.now(clock));
    }

    private Map<String, Object> schedulingSnapshot(Task task) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("isAvailable", task.isAvailable());
        snapshot.put("priorityScore", task.getPriorityScore());
        return snapshot;
    }

    private Map<String, Object> auditSnapshot(Task task) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", task.getTitle());
        snapshot.put("sourceType", task.getSourceType().name());
        snapshot.put("sourceRef", task.getSourceRef());
        snapshot.put("status", task.getStatus().name());
        return snapshot;
    }
}
