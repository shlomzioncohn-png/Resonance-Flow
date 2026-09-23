package com.rnd.bridge.publish.kafka;

import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.error.PublishException;
import com.rnd.bridge.task.BridgeTaskEventDto;
import com.rnd.bridge.task.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.ExecutionException;

/**
 * Publishes a {@link Task} onto Inbox's shared structured-tasks topic,
 * translated into {@link BridgeTaskEventDto} - the wire shape the contract
 * requires, not Task's own database shape. One topic for every source lane;
 * the platform travels in {@code sourceType}, not in the topic name.
 * <p>
 * The intake lane that owns this publisher processes one record at a time
 * and expects a failed publish to fail only that record, not the run - so
 * {@link #publish} blocks for the send result and turns a failure into a
 * {@link PublishException} rather than leaving it to resolve asynchronously
 * after the record has already been counted as published.
 */
public class KafkaPayloadPublisher implements PayloadPublisher<Task> {

    private static final Logger log = LoggerFactory.getLogger(KafkaPayloadPublisher.class);

    private final KafkaTemplate<String, BridgeTaskEventDto> kafkaTemplate;
    private final String topic;

    public KafkaPayloadPublisher(KafkaTemplate<String, BridgeTaskEventDto> kafkaTemplate, String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(Task task) {
        BridgeTaskEventDto event = BridgeTaskEventDto.from(task);
        try {
            kafkaTemplate.send(topic, task.dedupKey(), event).get();
            log.info("Published task {} to topic {} as event {}", task.dedupKey(), topic, event.eventId());
        } catch (ExecutionException e) {
            throw new PublishException(
                    "Failed to publish task " + task.dedupKey() + " to topic " + topic, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException(
                    "Interrupted while publishing task " + task.dedupKey() + " to topic " + topic, e);
        }
    }
}
