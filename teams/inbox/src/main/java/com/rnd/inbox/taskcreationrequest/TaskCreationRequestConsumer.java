package com.rnd.inbox.taskcreationrequest;

import com.rnd.inbox.event.TaskCreationRequestedEvent;
import com.rnd.inbox.shared.messaging.kafka.ErrorTopicPublisher;
import com.rnd.inbox.shared.messaging.kafka.EventProcessingTemplate;
import com.rnd.inbox.shared.messaging.validation.BeanValidationEventValidator;
import com.rnd.inbox.task.TaskIngestionService;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

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
                new BeanValidationEventValidator<>(validator),
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
