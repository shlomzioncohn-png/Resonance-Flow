package com.rnd.bridge.intake.lane;

import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.error.LaneException;
import com.rnd.bridge.raw.model.RawObjectEvent;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a validated RawObjectEvent to bridge.raw.landed, keyed by its dedup key
 * (contentHash). Blocks on the send future (bounded by PUBLISH_TIMEOUT_SECONDS) rather than
 * firing-and-forgetting it - a lane's per-record success/failure accounting (and anything
 * hung off a successful publish via AbstractIntakeLane.afterPublish, e.g. chat-group publish
 * confirmation) is only meaningful if a broker failure is actually observable here,
 * synchronously, instead of surfacing later on an unobserved future nobody checked.
 */
@Component
class KafkaRawObjectPublisher implements PayloadPublisher<RawObjectEvent> {

    private static final long PUBLISH_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    KafkaRawObjectPublisher(
            @Qualifier("kafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${bridge.intake.raw-landed-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(RawObjectEvent payload) {
        try {
            kafkaTemplate.send(topic, payload.dedupKey(), payload).get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LaneException(
                    "Interrupted while publishing to " + topic + " (dedupKey=" + payload.dedupKey() + ")", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new LaneException(
                    "Failed to publish to " + topic + " (dedupKey=" + payload.dedupKey() + ")", e);
        }
    }
}
