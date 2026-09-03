package com.rnd.inbox.bridge.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.messaging.kafka.ErrorTopicPublisher;
import com.rnd.inbox.messaging.kafka.EventProcessingTemplate;
import com.rnd.inbox.messaging.validation.BeanValidationEventValidator;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class BridgeTaskConsumer {

    private final EventProcessingTemplate<BridgeTaskEventDto> template;

    public BridgeTaskConsumer(ObjectMapper objectMapper, RetryTemplate kafkaRetryTemplate,
            ErrorTopicPublisher errorTopicPublisher, Validator validator,
            BridgeTaskMapper mapper, BridgeTaskPersistenceHandler persistenceHandler) {
        this.template = new EventProcessingTemplate<>(
                objectMapper,
                kafkaRetryTemplate,
                errorTopicPublisher,
                BridgeTaskEventDto.class,
                new BeanValidationEventValidator<>(validator),
                dto -> persistenceHandler.persist(mapper.map(dto), mapper.mapAssignment(dto), correlationUuidOf(dto)),
                BridgeTaskConsumer::correlationIdOf);
    }

    @KafkaListener(
            topics = "${inbox.kafka.topics.bridge-structured-tasks:bridge.structured.tasks}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        template.process(record, acknowledgment);
    }

    private static String correlationIdOf(BridgeTaskEventDto dto) {
        return dto.getEventId();
    }

    private static UUID correlationUuidOf(BridgeTaskEventDto dto) {
        return dto.getEventId() != null ? UUID.fromString(dto.getEventId()) : UUID.randomUUID();
    }
}
