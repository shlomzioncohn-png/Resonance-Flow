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

        TaskSchedulingUpdatedEvent event =
                new TaskSchedulingUpdatedEvent(
                        UUID.randomUUID(),
                        task.getId(),
                        task.isReady(),
                        task.getScore()
                );

        schedulingUpdatePublisher.publish(event);
    }
}