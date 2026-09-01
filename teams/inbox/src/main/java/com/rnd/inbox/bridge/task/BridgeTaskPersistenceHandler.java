package com.rnd.inbox.bridge.task;

import com.rnd.inbox.messaging.kafka.TransientProcessingException;
import com.rnd.inbox.model.entity.Assignment;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.service.TaskService;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class BridgeTaskPersistenceHandler {

    private final TaskService taskService;

    public BridgeTaskPersistenceHandler(TaskService taskService) {
        this.taskService = taskService;
    }

    public void persist(Task incoming, Assignment assignment, UUID correlationId) {
        try {
            taskService.createTask(incoming, assignment, correlationId);
        } catch (DataIntegrityViolationException e) {
            taskService.findExisting(incoming).orElseThrow(() -> e);
        } catch (DataAccessException e) {
            throw new TransientProcessingException("Technical failure while persisting task from bridge event", e);
        }
    }
}
