package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.events.SchedulingUpdatePublisher;
import com.rnd.chain.events.TaskSchedulingUpdatedEvent;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class SchedulingUpdateService {

    private final SchedulingUpdatePublisher schedulingUpdatePublisher;

    public SchedulingUpdateService(
            SchedulingUpdatePublisher schedulingUpdatePublisher) {

        this.schedulingUpdatePublisher = schedulingUpdatePublisher;
    }

    public void publishSchedulingUpdate(Task task) {

        // Issue #445: priorityScore is Chain's feasibility-adjusted value (PriorityScoreService),
        // not Mind's raw score - task.getScore() was a stand-in used before priorityScore existed.
        TaskSchedulingUpdatedEvent event =
                new TaskSchedulingUpdatedEvent(
                        UUID.randomUUID(),
                        task.getId(),
                        task.isReady(),
                        task.getPriorityScore()
                );

        schedulingUpdatePublisher.publish(event);
    }
}