package com.rnd.inbox.service;

import com.rnd.inbox.dto.TaskCreatedPayload;
import com.rnd.inbox.event.Event;
import com.rnd.inbox.event.EventPublisher;
import com.rnd.inbox.event.EventType;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.repository.TaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final EventPublisher eventPublisher;
    private final String sourceService;

    public TaskService(TaskRepository taskRepository, EventPublisher eventPublisher,
                        @Value("${spring.application.name}") String sourceService) {
        this.taskRepository = taskRepository;
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
}
