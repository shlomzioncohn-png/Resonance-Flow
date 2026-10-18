package com.rnd.bridge.media;

import com.rnd.bridge.raw.RawObjectEventProtoMapper;
import com.rnd.bridge.raw.RawObjectValidator;
import com.rnd.bridge.raw.model.RawObjectEvent;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes the media lane's RawObjectEvent to bridge.raw.landed as Protobuf - the same wire
 * format, template ({@code rawObjectEventKafkaTemplate}) and mapper
 * ({@link RawObjectEventProtoMapper}) the Unstructured-intake lane's KafkaRawObjectPublisher
 * uses, so the one topic both unstructured lanes share carries one format. The event is
 * validated first via the shared RawObjectValidator, so a malformed event never reaches Kafka.
 * <p>
 * The send is confirmed before returning (bounded by {@link #PUBLISH_TIMEOUT_SECONDS}): a
 * failure throws, so MediaIntakeOrchestrator rolls back its dedup claim and leaves the file in
 * place for the next run, rather than moving it to uploaded/ as if it had been published.
 */
@Service
public class MediaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MediaEventPublisher.class);
    private static final long PUBLISH_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RawObjectEventProtoMapper protoMapper;
    private final RawObjectValidator validator;
    private final String topic;

    public MediaEventPublisher(@Qualifier("rawObjectEventKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
                                RawObjectEventProtoMapper protoMapper,
                                RawObjectValidator validator,
                                @Value("${bridge.intake.raw-landed-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.protoMapper = protoMapper;
        this.validator = validator;
        this.topic = topic;
    }

    public void publish(RawObjectEvent event) {
        validator.validate(event);
        try {
            kafkaTemplate.send(topic, event.dedupKey(), protoMapper.toProto(event))
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while publishing to " + topic + " (dedupKey=" + event.dedupKey() + ")", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(
                    "Failed to publish to " + topic + " (dedupKey=" + event.dedupKey() + ")", e);
        }
        log.info("Published pointer event for correlationId={} ({} object(s)) to {}",
                event.correlationId(), event.objects().size(), topic);
    }
}
