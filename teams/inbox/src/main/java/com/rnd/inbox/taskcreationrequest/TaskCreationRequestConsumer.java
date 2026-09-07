package com.rnd.inbox.taskcreationrequest;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final EventProcessingTemplate<TaskCreationRequestEventDto> template;

    public TaskCreationRequestConsumer(ObjectMapper objectMapper, RetryTemplate kafkaRetryTemplate,
            ErrorTopicPublisher errorTopicPublisher, Validator validator,
            TaskIngestionService taskIngestionService) {
        this.template = new EventProcessingTemplate<>(
                objectMapper,
                kafkaRetryTemplate,
                errorTopicPublisher,
                TaskCreationRequestEventDto.class,
                new BeanValidationEventValidator<>(validator),
                taskIngestionService::ingest,
                TaskCreationRequestEventDto::getCorrelationId);
    }

    @KafkaListener(
            topics = "${inbox.kafka.topics.mind-task-creation-requested:task.creation.requested}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        template.process(record, acknowledgment);
    }
}
