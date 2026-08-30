package com.rnd.inbox.service;

import com.rnd.inbox.dto.TaskCreatedPayload;
import com.rnd.inbox.event.Event;
import com.rnd.inbox.event.EventPublisher;
import com.rnd.inbox.event.EventType;
import com.rnd.inbox.model.entity.AuditLog;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.repository.AuditLogRepository;
import com.rnd.inbox.repository.TaskRepository;
import org.springframework.beans.factory.annotation.Value;
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

    private final TaskRepository taskRepository;
    private final AuditLogRepository auditLogRepository;
    private final Clock clock;
    private final EventPublisher eventPublisher;
    private final String sourceService;

    public TaskService(TaskRepository taskRepository,
                       AuditLogRepository auditLogRepository,
                       Clock clock,
                       EventPublisher eventPublisher,
                       @Value("${spring.application.name}") String sourceService) {
        this.taskRepository = taskRepository;
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.sourceService = sourceService;
    }

    public Task createTask(Task task, UUID correlationId) {
        Task saved = taskRepository.save(task);

        TaskCreatedPayload payload = TaskCreatedPayload.from(saved);
        Event<TaskCreatedPayload> event = Event.newEvent(EventType.TASK_CREATED, sourceService, correlationId, payload);
        eventPublisher.publish(EventType.TASK_CREATED.getTopic(), saved.getId().toString(), event);

        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<Task> findExisting(Task incoming) {
        return taskRepository.findByOrganization_IdAndSourceTypeAndSourceRef(
                incoming.getOrganization().getId(), incoming.getSourceType(), incoming.getSourceRef());
    }

    @Transactional
    public Task createFromBridgeEvent(Task incoming, UUID correlationId) {
        Optional<Task> existing = findExisting(incoming);
        if (existing.isPresent()) {
            return existing.get();
        }

        Task saved = taskRepository.saveAndFlush(incoming);

        AuditLog audit = new AuditLog();
        audit.setOrganization(saved.getOrganization());
        audit.setAction("TASK_CREATED");
        audit.setEntityType("Task");
        audit.setEntityId(saved.getId());
        audit.setNewValues(auditSnapshot(saved));
        audit.setCorrelationId(correlationId);
        audit.setCreatedAt(Instant.now(clock));
        auditLogRepository.save(audit);

        return saved;
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
