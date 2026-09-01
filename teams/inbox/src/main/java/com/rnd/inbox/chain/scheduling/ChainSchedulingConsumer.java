package com.rnd.inbox.chain.scheduling;

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

@Component
public class ChainSchedulingConsumer {

    private final EventProcessingTemplate<ChainSchedulingEventDto> template;

    public ChainSchedulingConsumer(ObjectMapper objectMapper, RetryTemplate kafkaRetryTemplate,
            ErrorTopicPublisher errorTopicPublisher, Validator validator,
            ChainSchedulingPersistenceHandler persistenceHandler) {
        this.template = new EventProcessingTemplate<>(
                objectMapper,
                kafkaRetryTemplate,
                errorTopicPublisher,
                ChainSchedulingEventDto.class,
                new BeanValidationEventValidator<>(validator),
                persistenceHandler::apply,
                ChainSchedulingEventDto::getEventId);
    }

    @KafkaListener(
            topics = "${inbox.kafka.topics.chain-scheduling-updated:task.scheduling.updated}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        template.process(record, acknowledgment);
    }
}
