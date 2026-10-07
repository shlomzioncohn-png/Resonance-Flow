package com.rnd.inbox.taskcreationrequest;

import com.rnd.inbox.event.TaskCreationRequestedEvent;
import com.rnd.inbox.shared.messaging.kafka.CompositeEventValidator;
import com.rnd.inbox.shared.messaging.kafka.ErrorTopicPublisher;
import com.rnd.inbox.shared.messaging.kafka.EventProcessingTemplate;
import com.rnd.inbox.shared.messaging.validation.BeanValidationEventValidator;
import com.rnd.inbox.task.assignment.AssigneeConsistencyValidator;
import com.rnd.inbox.task.service.TaskIngestionService;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TaskCreationRequestConsumer {

    private final EventProcessingTemplate<TaskCreationRequestedEvent, TaskCreationRequestEventDto> template;

    public TaskCreationRequestConsumer(TaskCreationRequestProtoMapper protoMapper, RetryTemplate kafkaRetryTemplate,
            ErrorTopicPublisher errorTopicPublisher, Validator validator,
            TaskIngestionService taskIngestionService) {
        this.template = new EventProcessingTemplate<>(
                protoMapper::toDto,
                kafkaRetryTemplate,
                errorTopicPublisher,
                new CompositeEventValidator<>(List.of(
                        new BeanValidationEventValidator<>(validator),
                        new AssigneeConsistencyValidator<>(TaskCreationRequestEventDto::getAssignees))),
                taskIngestionService::ingest,
                TaskCreationRequestEventDto::getCorrelationId);
    }

    @KafkaListener(
            topics = "${inbox.kafka.topics.mind-task-creation-requested:task.creation.requested}",
            containerFactory = "taskCreationRequestedContainerFactory")
    public void onMessage(ConsumerRecord<String, TaskCreationRequestedEvent> record, Acknowledgment acknowledgment) {
        template.process(record, acknowledgment);
    }
}
