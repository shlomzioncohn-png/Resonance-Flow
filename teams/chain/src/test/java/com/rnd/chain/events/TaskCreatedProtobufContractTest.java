package com.rnd.chain.events;

import com.rnd.inbox.event.TaskCreatedEvent;

import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializerConfig;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TaskCreatedProtobufContractTest {

    private static final String TOPIC = "task.created";

    @Test
    void inboxSerializedTaskCreated_shouldBeReadableByChain() {

        String taskId = UUID.randomUUID().toString();

        TaskCreatedEvent original =
                TaskCreatedEvent.newBuilder()
                        .setId(taskId)
                        .build();

        Map<String, Object> serializerConfig =
                new HashMap<>();

        serializerConfig.put(
                "schema.registry.url",
                "mock://task-created-contract-test");

        KafkaProtobufSerializer<TaskCreatedEvent> serializer =
                new KafkaProtobufSerializer<>();

        serializer.configure(serializerConfig, false);

        byte[] bytes =
                serializer.serialize(TOPIC, original);

        assertNotNull(bytes);

        Map<String, Object> deserializerConfig =
                new HashMap<>();

        deserializerConfig.put(
                "schema.registry.url",
                "mock://task-created-contract-test");

        deserializerConfig.put(
                KafkaProtobufDeserializerConfig
                        .SPECIFIC_PROTOBUF_VALUE_TYPE,
                TaskCreatedEvent.class.getName());

        KafkaProtobufDeserializer<TaskCreatedEvent> deserializer =
                new KafkaProtobufDeserializer<>();

        deserializer.configure(
                deserializerConfig,
                false);

        TaskCreatedEvent result =
                deserializer.deserialize(
                        TOPIC,
                        bytes);

        assertNotNull(result);

        assertEquals(
                taskId,
                result.getId());

        serializer.close();
        deserializer.close();
    }
}