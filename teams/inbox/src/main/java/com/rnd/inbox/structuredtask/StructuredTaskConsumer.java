package com.rnd.inbox.structuredtask;

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
public class StructuredTaskConsumer {

    private final EventProcessingTemplate<StructuredTaskEventDto> template;

    public StructuredTaskConsumer(ObjectMapper objectMapper, RetryTemplate kafkaRetryTemplate,
            ErrorTopicPublisher errorTopicPublisher, Validator validator,
            StructuredTaskMapper mapper, TaskIngestionPersistenceHandler persistenceHandler) {
        this.template = new EventProcessingTemplate<>(
                objectMapper,
                kafkaRetryTemplate,
                errorTopicPublisher,
                StructuredTaskEventDto.class,
                new BeanValidationEventValidator<>(validator),
                dto -> persistenceHandler.persist(mapper.map(dto), mapper.mapAssignment(dto), correlationUuidOf(dto)),
                StructuredTaskConsumer::correlationIdOf);
    }

    @KafkaListener(
            topics = "${inbox.kafka.topics.bridge-structured-tasks:bridge.structured.tasks}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        template.process(record, acknowledgment);
    }

    private static String correlationIdOf(StructuredTaskEventDto dto) {
        return dto.getEventId();
    }

    private static UUID correlationUuidOf(StructuredTaskEventDto dto) {
        return dto.getEventId() != null ? UUID.fromString(dto.getEventId()) : UUID.randomUUID();
    }
}
