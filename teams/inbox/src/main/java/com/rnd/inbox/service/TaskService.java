package com.rnd.inbox.service;

import com.rnd.inbox.dto.TaskCreatedPayload;
import com.rnd.inbox.event.Event;
import com.rnd.inbox.event.EventPublisher;
import com.rnd.inbox.event.EventType;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.repository.TaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final AuditService auditService;
    private final EventPublisher eventPublisher;
    private final String sourceService;

    public TaskService(TaskRepository taskRepository,
                       AuditService auditService,
                       EventPublisher eventPublisher,
                       @Value("${spring.application.name}") String sourceService) {
        this.taskRepository = taskRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.sourceService = sourceService;
    }

    @Transactional(readOnly = true)
    public Optional<Task> findExisting(Task incoming) {
        return taskRepository.findByOrganization_IdAndSourceTypeAndSourceRef(
                incoming.getOrganization().getId(), incoming.getSourceType(), incoming.getSourceRef());
    }

    @Transactional
    public Task createTask(Task incoming, UUID correlationId) {
        Optional<Task> existing = findExisting(incoming);
        if (existing.isPresent()) {
            return existing.get();
        }

        Task saved = insertTask(incoming);
        auditService.createAuditLog("TASK_CREATED", "Task", saved.getId(), saved.getOrganization(),
                auditSnapshot(saved), correlationId);
        return saved;
    }

    private Task insertTask(Task incoming) {
        return taskRepository.saveAndFlush(incoming);
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
