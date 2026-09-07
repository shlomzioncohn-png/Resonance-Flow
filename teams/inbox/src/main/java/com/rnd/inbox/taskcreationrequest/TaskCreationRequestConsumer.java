package com.rnd.inbox.taskcreationrequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.shared.messaging.kafka.ErrorTopicPublisher;
import com.rnd.inbox.shared.messaging.kafka.EventProcessingTemplate;
import com.rnd.inbox.task.TaskIngestionPersistenceHandler;
import com.rnd.inbox.shared.messaging.validation.BeanValidationEventValidator;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TaskCreationRequestConsumer {

    private final EventProcessingTemplate<TaskCreationRequestEventDto> template;

    public TaskCreationRequestConsumer(ObjectMapper objectMapper, RetryTemplate kafkaRetryTemplate,
            ErrorTopicPublisher errorTopicPublisher, Validator validator,
            TaskCreationRequestMapper mapper, TaskIngestionPersistenceHandler persistenceHandler) {
        this.template = new EventProcessingTemplate<>(
                objectMapper,
                kafkaRetryTemplate,
                errorTopicPublisher,
                TaskCreationRequestEventDto.class,
                new BeanValidationEventValidator<>(validator),
                dto -> persistenceHandler.persist(mapper.map(dto), correlationUuidOf(dto)),
                TaskCreationRequestEventDto::getCorrelationId);
    }

    @KafkaListener(
            topics = "${inbox.kafka.topics.mind-task-creation-requested:task.creation.requested}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        template.process(record, acknowledgment);
    }

    private static UUID correlationUuidOf(TaskCreationRequestEventDto dto) {
        return UUID.fromString(dto.getCorrelationId());
    }
}
