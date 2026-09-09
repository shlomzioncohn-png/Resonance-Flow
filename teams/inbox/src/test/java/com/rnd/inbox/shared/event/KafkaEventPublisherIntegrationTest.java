package com.rnd.inbox.shared.event;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaEventPublisherIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisherIntegrationTest.class);

    private static final String TOPIC = "task.created";

    private static EmbeddedKafkaBroker broker;

    private DefaultKafkaProducerFactory<String, Object> producerFactory;
    private ScheduledExecutorService retryScheduler;
    private KafkaEventPublisher publisher;
    private Consumer<String, String> consumer;

    @BeforeAll
    static void startBroker() throws Exception {
        broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC).kafkaPorts(0);
        broker.afterPropertiesSet();
    }

    @AfterAll
    static void stopBroker() {
        try {
            broker.destroy();
        } catch (Exception ex) {
            log.warn("Failed to cleanly shut down the embedded Kafka broker; ignoring since the tests already ran", ex);
        }
    }

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(broker);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        producerProps.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        producerFactory = new DefaultKafkaProducerFactory<>(producerProps);
        KafkaTemplate<String, Object> kafkaTemplate = new KafkaTemplate<>(producerFactory);

        retryScheduler = Executors.newSingleThreadScheduledExecutor();
        // No ProtoEventMapper registered here, so every event falls back to the JSON template;
        // the protobuf template argument is required but never exercised in this test.
        publisher = new KafkaEventPublisher(kafkaTemplate, kafkaTemplate, List.of(), retryScheduler, 3, 50L, 2.0, 500L);

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("kafka-event-publisher-it", "true", broker);
        DefaultKafkaConsumerFactory<String, String> consumerFactory =
                new DefaultKafkaConsumerFactory<>(consumerProps, new StringDeserializer(), new StringDeserializer());
        consumer = consumerFactory.createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, TOPIC);
    }

    @AfterEach
    void tearDown() {
        consumer.close();
        producerFactory.destroy();
        retryScheduler.shutdownNow();
    }

    @Test
    void publish_sendsRealMessageWithMetadataHeadersAndPayload() throws Exception {
        UUID correlationId = UUID.randomUUID();
        SamplePayload payload = new SamplePayload("task-123", "Write the report");
        Event<SamplePayload> event = Event.newEvent(EventType.TASK_CREATED, "inbox", correlationId, payload);

        CompletableFuture<Void> result = publisher.publish(TOPIC, "task-123", event);
        result.get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, TOPIC);

        assertThat(record.key()).isEqualTo("task-123");
        assertThat(headerValue(record, "event_id")).isEqualTo(event.getEventId().toString());
        assertThat(headerValue(record, "event_type")).isEqualTo("task.created");
        assertThat(headerValue(record, "source_service")).isEqualTo("inbox");
        assertThat(headerValue(record, "correlation_id")).isEqualTo(correlationId.toString());
        assertThat(record.headers().lastHeader("__TypeId__")).isNull();
        assertThat(record.value()).contains("\"id\":\"task-123\"", "\"title\":\"Write the report\"");
    }

    private static String headerValue(ConsumerRecord<String, String> record, String key) {
        return new String(record.headers().lastHeader(key).value(), StandardCharsets.UTF_8);
    }

    private static final class SamplePayload {
        private final String id;
        private final String title;

        private SamplePayload(String id, String title) {
            this.id = id;
            this.title = title;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }
    }
}
