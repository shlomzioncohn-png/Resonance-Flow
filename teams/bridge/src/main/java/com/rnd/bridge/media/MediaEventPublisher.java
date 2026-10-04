package com.rnd.bridge.media;

import com.rnd.bridge.raw.RawObjectValidator;
import com.rnd.bridge.raw.model.RawObjectEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes a RawObjectEvent to bridge.raw.landed, validating it first via the shared
 * RawObjectValidator (raw/) so a malformed event never reaches Kafka - mirrors
 * intake.service.PointerEventPublisher's build-then-validate-then-send shape. Publishes to
 * the topic bean the Unstructured-intake lane already declares
 * (shared.unstructured.config.KafkaTopicConfig) - both unstructured lanes share one topic,
 * so it's declared once rather than redeclared here.
 */
@Service
public class MediaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MediaEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RawObjectValidator validator;
    private final String topic;

    public MediaEventPublisher(@Qualifier("kafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
                                RawObjectValidator validator,
                                @Value("${bridge.intake.raw-landed-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.validator = validator;
        this.topic = topic;
    }

    public void publish(RawObjectEvent event) {
        validator.validate(event);
        kafkaTemplate.send(topic, event.contentHash(), event);
        log.info("Published pointer event for {} to {}", event.objectKey(), topic);
    }
}
