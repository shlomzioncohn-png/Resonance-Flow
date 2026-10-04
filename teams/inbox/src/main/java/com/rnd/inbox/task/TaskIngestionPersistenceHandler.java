package com.rnd.inbox.task;

import com.rnd.inbox.shared.messaging.kafka.TransientProcessingException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TaskIngestionPersistenceHandler {

    private final TaskService taskService;

    public TaskIngestionPersistenceHandler(TaskService taskService) {
        this.taskService = taskService;
    }

    public void persist(Task incoming, UUID correlationId) {
        persist(incoming, null, correlationId);
    }

    public void persist(Task incoming, Assignment assignment, UUID correlationId) {
        try {
            taskService.createTask(incoming, assignment, correlationId);
        } catch (DataIntegrityViolationException e) {
            taskService.findExisting(incoming).orElseThrow(() -> e);
        } catch (DataAccessException e) {
            throw new TransientProcessingException("Technical failure while persisting task from ingestion event", e);
        }
    }
}
