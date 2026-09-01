package com.rnd.bridge.intake.lane;

import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.raw.model.RawObjectEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes a validated RawObjectEvent to bridge.raw.landed, keyed by its dedup key (contentHash). */
@Component
class KafkaRawObjectPublisher implements PayloadPublisher<RawObjectEvent> {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    KafkaRawObjectPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${bridge.intake.raw-landed-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(RawObjectEvent payload) {
        kafkaTemplate.send(topic, payload.dedupKey(), payload);
    }
}
