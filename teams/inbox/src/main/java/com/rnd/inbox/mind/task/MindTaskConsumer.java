package com.rnd.inbox.mind.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.messaging.kafka.ErrorTopicPublisher;
import com.rnd.inbox.messaging.kafka.EventProcessingTemplate;
import com.rnd.inbox.messaging.task.TaskIngestionPersistenceHandler;
import com.rnd.inbox.messaging.validation.BeanValidationEventValidator;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MindTaskConsumer {

    private final EventProcessingTemplate<MindTaskEventDto> template;

    public MindTaskConsumer(ObjectMapper objectMapper, RetryTemplate kafkaRetryTemplate,
            ErrorTopicPublisher errorTopicPublisher, Validator validator,
            MindTaskMapper mapper, TaskIngestionPersistenceHandler persistenceHandler) {
        this.template = new EventProcessingTemplate<>(
                objectMapper,
                kafkaRetryTemplate,
                errorTopicPublisher,
                MindTaskEventDto.class,
                new BeanValidationEventValidator<>(validator),
                dto -> persistenceHandler.persist(mapper.map(dto), correlationUuidOf(dto)),
                MindTaskEventDto::getCorrelationId);
    }

    @KafkaListener(
            topics = "${inbox.kafka.topics.mind-task-creation-requested:task.creation.requested}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        template.process(record, acknowledgment);
    }

    private static UUID correlationUuidOf(MindTaskEventDto dto) {
        return UUID.fromString(dto.getCorrelationId());
    }
}
