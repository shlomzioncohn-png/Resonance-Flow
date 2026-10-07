package com.rnd.chain.events;

import com.rnd.inbox.event.TaskCreatedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TaskCreatedEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(TaskCreatedEventConsumer.class);

    private final TaskCreatedEventService taskCreatedEventService;

    public TaskCreatedEventConsumer(
            TaskCreatedEventService taskCreatedEventService) {

        this.taskCreatedEventService =
                taskCreatedEventService;
    }

    @KafkaListener(
            topics = "${chain.kafka.topics.task-created}",
            groupId = "${chain.kafka.consumer-group-id}",
            containerFactory = "protobufKafkaListenerContainerFactory")
    public void onMessage(TaskCreatedEvent event) {

        log.info(
                "Received task.created for task [{}]",
                event.getId());

        taskCreatedEventService.handle(event);
    }
}