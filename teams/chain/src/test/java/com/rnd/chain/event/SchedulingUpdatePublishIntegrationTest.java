package com.rnd.chain.event;

import com.rnd.chain.events.ChainEventTopicsProperties;
import com.rnd.chain.events.ChainProtobufKafkaConfig;
import com.rnd.inbox.event.SchedulingUpdatedEvent;
import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema;
import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchemaProvider;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializerConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.condition.EmbeddedKafkaCondition;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishes through Chain's real Protobuf producer wiring to an embedded broker and reads it back
 * with Inbox's message class, the way Inbox's consumer does. The registry is a {@code mock://}
 * one, same as the other embedded-Kafka tests here.
 */
@EmbeddedKafka(partitions = 1, topics = "task.scheduling.updated")
class SchedulingUpdatePublishIntegrationTest {

    private static final String TOPIC = "task.scheduling.updated";
    private static final String REGISTRY = "mock://scheduling-publish-test";

    @Test
    void publishedEvent_reachesTheBrokerAndDecodesAsInboxsMessage() throws Exception {
        // The producer never registers schemas; Relay's schema-sync does, so the test plays that part.
        MockSchemaRegistry.getClientForScope("scheduling-publish-test", List.of(new ProtobufSchemaProvider()))
                .register(TOPIC + "-value", new ProtobufSchema(SchedulingUpdatedEvent.getDescriptor()));
        EmbeddedKafkaBroker broker = EmbeddedKafkaCondition.getBroker();
        KafkaSchedulingUpdatePublisher publisher = new KafkaSchedulingUpdatePublisher(
                producerTemplate(broker.getBrokersAsString()),
                new ChainEventTopicsProperties(Map.of("task.scheduling.updated", TOPIC), null));
        UUID eventId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        publisher.publish(new TaskSchedulingUpdatedEvent(eventId, taskId, true, 6.5));

        try (Consumer<String, SchedulingUpdatedEvent> consumer = consumer(broker)) {
            ConsumerRecord<String, SchedulingUpdatedEvent> record =
                    KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(20));

            assertNotNull(record);
            assertEquals(taskId.toString(), record.key());
            assertEquals(eventId.toString(), record.value().getEventId());
            assertEquals(taskId.toString(), record.value().getTaskId());
            assertTrue(record.value().getAvailable());
            assertEquals(6.5, record.value().getPriorityScore());
        }
    }

    private static org.springframework.kafka.core.KafkaTemplate<String, SchedulingUpdatedEvent>
            producerTemplate(String bootstrapServers) {
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(List.of(bootstrapServers));
        ChainProtobufKafkaConfig config = new ChainProtobufKafkaConfig(kafkaProperties);
        ReflectionTestUtils.setField(config, "schemaRegistryUrl", REGISTRY);
        return config.schedulingUpdatedEventKafkaTemplate();
    }

    private static Consumer<String, SchedulingUpdatedEvent> consumer(EmbeddedKafkaBroker broker) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("scheduling-publish-test", "true", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(KafkaProtobufDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, REGISTRY);
        props.put(KafkaProtobufDeserializerConfig.SPECIFIC_PROTOBUF_VALUE_TYPE, SchedulingUpdatedEvent.class.getName());
        Consumer<String, SchedulingUpdatedEvent> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new KafkaProtobufDeserializer<SchedulingUpdatedEvent>())
                .createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, TOPIC);
        return consumer;
    }
}
