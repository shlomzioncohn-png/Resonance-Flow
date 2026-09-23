package com.rnd.inbox.shared.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Message;
import com.rnd.inbox.event.TaskCompletedEvent;
import com.rnd.inbox.event.TaskCreatedEvent;
import com.rnd.inbox.event.TaskUpdatedEvent;
import com.rnd.inbox.task.TaskCompletedProtoMapper;
import com.rnd.inbox.task.TaskCreatedProtoMapper;
import com.rnd.inbox.task.TaskUpdatedProtoMapper;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoEventPublishingIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ProtoEventPublishingIntegrationTest.class);

    private static final String CREATED_TOPIC = "task.created";
    private static final String UPDATED_TOPIC = "task.updated";
    private static final String COMPLETED_TOPIC = "task.completed";

    private static EmbeddedKafkaBroker broker;
    private static SchemaRegistryClient schemaRegistryClient;

    private DefaultKafkaProducerFactory<String, Object> producerFactory;
    private ScheduledExecutorService retryScheduler;
    private KafkaEventPublisher publisher;

    @BeforeAll
    static void startBroker() throws Exception {
        broker = new EmbeddedKafkaKraftBroker(1, 1, CREATED_TOPIC, UPDATED_TOPIC, COMPLETED_TOPIC).kafkaPorts(0);
        broker.afterPropertiesSet();
        schemaRegistryClient = new MockSchemaRegistryClient();
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
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(broker);

        producerProps.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "mock://not-dialed");
        producerFactory = new DefaultKafkaProducerFactory<>(
                producerProps, new StringSerializer(), new KafkaProtobufSerializer(schemaRegistryClient));
        KafkaTemplate<String, Object> protobufKafkaTemplate = new KafkaTemplate<>(producerFactory);

        retryScheduler = Executors.newSingleThreadScheduledExecutor();

        publisher = new KafkaEventPublisher(
                protobufKafkaTemplate,
                List.of(new TaskCreatedProtoMapper(new ObjectMapper()), new TaskUpdatedProtoMapper(), new TaskCompletedProtoMapper()),
                retryScheduler, 3, 50L, 2.0, 500L);
    }

    @AfterEach
    void tearDown() {
        producerFactory.destroy();
        retryScheduler.shutdownNow();
    }

    @Test
    void publish_taskCreated_roundTripsThroughRealProtobufSerializationAndSchemaRegistry() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-10T09:00:00Z");
        Event<Map<String, Object>> event = Event.newEvent(
                EventType.TASK_CREATED, "inbox", null, taskCreatedPayload(taskId, tenantId, createdAt));

        CompletableFuture<Void> result = publisher.publish(CREATED_TOPIC, taskId.toString(), event);
        result.get(10, TimeUnit.SECONDS);

        TaskCreatedEvent received = consumeOne(CREATED_TOPIC, TaskCreatedEvent.class);

        assertThat(received.getId()).isEqualTo(taskId.toString());
        assertThat(received.getTenantId()).isEqualTo(tenantId.toString());
        assertThat(received.getTitle()).isEqualTo("Write the quarterly report");
        assertThat(received.getIsAvailable()).isFalse();
        assertThat(received.hasProjectId()).isFalse();
    }

    @Test
    void publish_taskUpdated_roundTripsThroughRealProtobufSerializationAndSchemaRegistry() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Instant updatedAt = Instant.parse("2026-09-09T12:00:00Z");
        Event<Map<String, Object>> event = Event.newEvent(
                EventType.TASK_UPDATED, "inbox", null, taskUpdatedPayload(taskId, tenantId, updatedAt));

        CompletableFuture<Void> result = publisher.publish(UPDATED_TOPIC, taskId.toString(), event);
        result.get(10, TimeUnit.SECONDS);

        TaskUpdatedEvent received = consumeOne(UPDATED_TOPIC, TaskUpdatedEvent.class);

        assertThat(received.getTaskId()).isEqualTo(taskId.toString());
        assertThat(received.getTenantId()).isEqualTo(tenantId.toString());
        assertThat(received.getTitle()).isEqualTo("Prepare release notes");
        assertThat(received.getIsAvailable()).isTrue();
        assertThat(received.hasProjectId()).isFalse();
    }

    @Test
    void publish_taskCompleted_roundTripsThroughRealProtobufSerializationAndSchemaRegistry() throws Exception {
        UUID taskId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId.toString());
        payload.put("completed", true);
        Event<Map<String, Object>> event = Event.newEvent(EventType.TASK_COMPLETED, "inbox", null, payload);

        CompletableFuture<Void> result = publisher.publish(COMPLETED_TOPIC, taskId.toString(), event);
        result.get(10, TimeUnit.SECONDS);

        TaskCompletedEvent received = consumeOne(COMPLETED_TOPIC, TaskCompletedEvent.class);

        assertThat(received.getTaskId()).isEqualTo(taskId.toString());
        assertThat(received.getIsCompleted()).isTrue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends Message> T consumeOne(String topic, Class<T> protoType) {
        Map<String, Object> consumerProps =
                KafkaTestUtils.consumerProps("proto-it-" + UUID.randomUUID(), "true", broker);
        consumerProps.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "mock://not-dialed");
        consumerProps.put("specific.protobuf.value.type", protoType.getName());

        DefaultKafkaConsumerFactory<String, Object> consumerFactory = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new KafkaProtobufDeserializer(schemaRegistryClient));
        try (Consumer<String, Object> consumer = consumerFactory.createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, topic);
            ConsumerRecord<String, Object> record = KafkaTestUtils.getSingleRecord(consumer, topic);
            return protoType.cast(record.value());
        }
    }

    private Map<String, Object> taskCreatedPayload(UUID taskId, UUID tenantId, Instant createdAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId.toString());
        payload.put("tenantId", tenantId.toString());
        payload.put("title", "Write the quarterly report");
        payload.put("description", "Summarize Q3 numbers");
        payload.put("available", false);
        payload.put("sourceType", "MANUAL");
        payload.put("sourceRef", "manual:1");
        payload.put("status", "NEW");
        payload.put("extractionProvenance", "MANUAL");
        payload.put("createdAt", createdAt.toString());
        return payload;
    }

    private Map<String, Object> taskUpdatedPayload(UUID taskId, UUID tenantId, Instant updatedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId.toString());
        payload.put("tenantId", tenantId.toString());
        payload.put("title", "Prepare release notes");
        payload.put("description", "Summarize what shipped this sprint");
        payload.put("available", true);
        payload.put("sourceType", "MANUAL");
        payload.put("sourceRef", "manual:1");
        payload.put("status", "IN_REVIEW");
        payload.put("extractionProvenance", "MANUAL");
        payload.put("updatedAt", updatedAt.toString());
        return payload;
    }
}
