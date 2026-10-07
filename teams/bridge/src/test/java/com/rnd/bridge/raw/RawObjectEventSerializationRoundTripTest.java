package com.rnd.bridge.raw;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.InvalidProtocolBufferException;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import com.rnd.bridge.raw.model.ScopeDecision;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves the actual wire bytes round-trip through a real KafkaProtobufSerializer/
 * KafkaProtobufDeserializer pair, not just that the mapper builds a well-formed message in
 * memory - uses a MockSchemaRegistryClient (same approach as Inbox's
 * ProtoEventPublishingIntegrationTest) rather than a live registry or embedded broker, since
 * this is about proving the serde round-trip, not transport.
 * <p>
 * Deserializes generically (no specific.protobuf.value.type config) and reparses the result's
 * raw bytes into the concrete generated type via parseFrom - the 2-arg
 * KafkaProtobufDeserializer(SchemaRegistryClient, Map) constructor does not honor that config
 * key the way a Spring Kafka consumer factory does (it returns a DynamicMessage regardless),
 * verified empirically rather than assumed.
 */
class RawObjectEventSerializationRoundTripTest {

    private static final String TOPIC = "bridge.raw.landed";

    private final RawObjectEventProtoMapper mapper = new RawObjectEventProtoMapper();
    private final SchemaRegistryClient schemaRegistryClient = new MockSchemaRegistryClient();

    @Test
    void protobufBytesRoundTripThroughRealSerializerAndDeserializer() throws InvalidProtocolBufferException {
        RawObjectRef transcript = new RawObjectRef(
                Role.TRANSCRIPT, "SLACK/kamatech/2026-08-18/x.json", "sha256:" + "a".repeat(64), "audio/mpeg",
                456L, Modality.AUDIO);
        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.SLACK, "kamatech", Instant.parse("2026-08-18T09:15:00Z"), ScopeDecision.INCLUDED,
                List.of("ruth"), Instant.parse("2026-08-18T09:20:00Z"), "corr-1", 0, ChangeType.UPSERT,
                List.of(transcript));
        com.rnd.bridge.shared.unstructured.dto.RawObjectEvent protoEvent = mapper.toProto(event);

        byte[] bytes;
        try (KafkaProtobufSerializer<com.rnd.bridge.shared.unstructured.dto.RawObjectEvent> serializer =
                new KafkaProtobufSerializer<>(schemaRegistryClient,
                        Map.of("schema.registry.url", "mock://not-dialed", "auto.register.schemas", true))) {
            bytes = serializer.serialize(TOPIC, protoEvent);
        }

        com.google.protobuf.Message genericDeserialized;
        try (KafkaProtobufDeserializer<com.google.protobuf.Message> deserializer =
                new KafkaProtobufDeserializer<>(schemaRegistryClient,
                        Map.of("schema.registry.url", "mock://not-dialed"))) {
            genericDeserialized = deserializer.deserialize(TOPIC, bytes);
        }

        com.rnd.bridge.shared.unstructured.dto.RawObjectEvent deserialized =
                com.rnd.bridge.shared.unstructured.dto.RawObjectEvent.parseFrom(genericDeserialized.toByteArray());

        assertThat(deserialized).isEqualTo(protoEvent);
        assertThat(deserialized.getCorrelationId()).isEqualTo("corr-1");
        assertThat(deserialized.getChangeSignificanceScore()).isEqualTo(100);
        assertThat(deserialized.getObjectsList()).hasSize(1);
        assertThat(deserialized.getObjects(0).getRole())
                .isEqualTo(com.rnd.bridge.shared.unstructured.dto.Role.TRANSCRIPT);
    }
}
