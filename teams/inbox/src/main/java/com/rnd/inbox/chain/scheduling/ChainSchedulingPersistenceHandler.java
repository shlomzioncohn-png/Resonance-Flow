package com.rnd.inbox.chain.scheduling;

import com.rnd.inbox.messaging.kafka.TransientProcessingException;
import com.rnd.inbox.service.TaskService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ChainSchedulingPersistenceHandler {

    private final TaskService taskService;

    public ChainSchedulingPersistenceHandler(TaskService taskService) {
        this.taskService = taskService;
    }

    public void apply(ChainSchedulingEventDto dto) {
        UUID taskId = UUID.fromString(dto.getTaskId());
        Float priorityScore = dto.getPriorityScore() != null ? dto.getPriorityScore().floatValue() : null;
        UUID correlationId = dto.getEventId() != null ? UUID.fromString(dto.getEventId()) : UUID.randomUUID();

        try {
            taskService.applySchedulingUpdate(taskId, dto.getAvailable(), priorityScore, correlationId);
        } catch (DataAccessException e) {
            throw new TransientProcessingException("Technical failure while applying scheduling update", e);
        }
    }
}
