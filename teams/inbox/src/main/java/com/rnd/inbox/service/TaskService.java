package com.rnd.inbox.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.dto.TaskCreatedPayload;
import com.rnd.inbox.event.EventType;
import com.rnd.inbox.exception.TaskNotFoundException;
import com.rnd.inbox.model.entity.Assignment;
import com.rnd.inbox.model.entity.OutboxEvent;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.repository.AssignmentRepository;
import com.rnd.inbox.repository.OutboxEventRepository;
import com.rnd.inbox.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
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
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository,
                       AuditService auditService,
                       OutboxEventRepository outboxEventRepository,
                       AssignmentRepository assignmentRepository,
                       ObjectMapper objectMapper,
                       Clock clock) {
        this.taskRepository = taskRepository;
        this.auditService = auditService;
        this.outboxEventRepository = outboxEventRepository;
        this.assignmentRepository = assignmentRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public Task createTask(Task incoming, UUID correlationId) {
        return createTask(incoming, null, correlationId);
    }

    @Transactional
    public Task createTask(Task incoming, Assignment assignment, UUID correlationId) {
        Optional<Task> existing = findExisting(incoming);
        if (existing.isPresent()) {
            return existing.get();
        }

        Task saved = insertTask(incoming);

        if (assignment != null) {
            assignment.setTask(saved);
            assignmentRepository.save(assignment);
        }

        outboxEventRepository.save(buildTaskCreatedOutboxEvent(saved, correlationId));
        auditService.createAuditLog("TASK_CREATED", "Task", saved.getId(), saved.getOrganization(),
                auditSnapshot(saved), correlationId);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<Task> findExisting(Task incoming) {
        return taskRepository.findByOrganization_IdAndSourceTypeAndSourceRef(
                incoming.getOrganization().getId(), incoming.getSourceType(), incoming.getSourceRef());
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

        outboxEventRepository.save(buildTaskUpdatedOutboxEvent(saved, correlationId));
        auditService.createAuditLog("TASK_SCHEDULING_UPDATED", "Task", saved.getId(), saved.getOrganization(),
                schedulingSnapshot(saved), correlationId);
        return saved;
    }

    private Task insertTask(Task incoming) {
        return taskRepository.saveAndFlush(incoming);
    }

    private OutboxEvent buildTaskCreatedOutboxEvent(Task saved, UUID correlationId) {
        Map<String, Object> payload = objectMapper.convertValue(TaskCreatedPayload.from(saved), PAYLOAD_TYPE);

        return OutboxEvent.pending(
                EventType.TASK_CREATED,
                saved.getId(),
                saved.getId().toString(),
                payload,
                correlationId,
                Instant.now(clock));
    }

    private OutboxEvent buildTaskUpdatedOutboxEvent(Task saved, UUID correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", saved.getId().toString());
        payload.put("isAvailable", saved.isAvailable());
        payload.put("priorityScore", saved.getPriorityScore());

        return OutboxEvent.pending(
                EventType.TASK_UPDATED,
                saved.getId(),
                saved.getId().toString(),
                payload,
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
