package com.rnd.bridge.task;

import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
import com.rnd.inbox.event.StructuredTaskEvent;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the actual Confluent Protobuf wire format {@link
 * com.rnd.bridge.publish.kafka.BridgeTaskKafkaConfig#bridgeTaskEventKafkaTemplate} produces onto,
 * using a {@link MockSchemaRegistryClient} so no network call or real registry is needed - see
 * issue #567. No {@code KafkaTemplate}/Spring context involved; this is one step below {@link
 * com.rnd.bridge.publish.kafka.KafkaPayloadPublisherTest}, which mocks the template and never
 * touches real serialization at all.
 */
class StructuredTaskEventWireFormatTest {

    private static final String TOPIC = "bridge.structured.tasks";

    private final SchemaRegistryClient schemaRegistryClient = new MockSchemaRegistryClient();
    // The client is injected directly below, so this is never dialed - Confluent's serializer
    // config still requires the property to be present, same placeholder Inbox's own
    // ProtoEventPublishingIntegrationTest uses.
    private final Map<String, Object> serdeConfig =
            Map.of(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "mock://not-dialed");

    @Test
    void serializedBytesStartWithTheConfluentMagicByte() {
        Task task = fullyPopulatedTask();
        StructuredTaskEvent event = StructuredTaskEventMapper.from(task);

        byte[] bytes = serialize(event);

        assertThat(bytes[0]).isEqualTo((byte) 0);
    }

    @Test
    void roundTripsAFullyPopulatedTaskWithEveryOptionalFieldSet() {
        Task task = fullyPopulatedTask();
        StructuredTaskEvent sent = StructuredTaskEventMapper.from(task);

        StructuredTaskEvent received = deserialize(serialize(sent));

        assertThat(received.getEventId()).isEqualTo(sent.getEventId());
        assertThat(received.getTenantId()).isEqualTo(task.organizationId());
        assertThat(received.getSourceRef()).isEqualTo(task.sourceRef());
        assertThat(received.getSourceType()).isEqualTo(com.rnd.inbox.event.SourceType.SOURCE_TYPE_CRM_DEAL);
        assertThat(received.getTitle()).isEqualTo(task.title());
        assertThat(received.hasDescription()).isTrue();
        assertThat(received.getDescription()).isEqualTo(task.description());
        assertThat(received.getStatus()).isEqualTo(com.rnd.inbox.event.TaskStatus.TASK_STATUS_NEW);
        assertThat(received.hasUrgency()).isTrue();
        assertThat(received.getUrgency()).isEqualTo(task.urgency());
        assertThat(received.hasConfidence()).isTrue();
        assertThat(received.getConfidence()).isEqualTo(task.confidence());
        assertThat(received.getTier()).isEqualTo(com.rnd.inbox.event.TaskTier.TASK_TIER_TACTICAL);
        assertThat(received.hasRoleContext()).isTrue();
        assertThat(received.getRoleContext()).isEqualTo(com.rnd.inbox.event.TaskRoleContext.TASK_ROLE_CONTEXT_SALESPERSON);
        assertThat(received.hasProjectId()).isTrue();
        assertThat(received.getProjectId()).isEqualTo(task.projectId());
        assertThat(received.hasEffortEstimateMinutes()).isTrue();
        assertThat(received.getEffortEstimateMinutes()).isEqualTo(task.effortEstimateMinutes());
        assertThat(received.hasDeadline()).isTrue();
        assertThat(Instant.ofEpochSecond(received.getDeadline().getSeconds(), received.getDeadline().getNanos()))
                .isEqualTo(task.deadline());
    }

    @Test
    void roundTripsATaskWithEveryOptionalFieldUnset() {
        Task minimal = new Task(
                "11111111-1111-1111-1111-111111111111", null,
                "Minimal title", null,
                TaskStatus.NEW, null, null,
                null, ExtractionProvenance.ETL, null,
                SourceType.CRM_DEAL, "crm:D-minimal",
                null, null, null);
        StructuredTaskEvent sent = StructuredTaskEventMapper.from(minimal);

        StructuredTaskEvent received = deserialize(serialize(sent));

        assertThat(received.hasDescription()).isFalse();
        assertThat(received.hasUrgency()).isFalse();
        assertThat(received.hasConfidence()).isFalse();
        assertThat(received.hasTier()).isFalse();
        assertThat(received.hasRoleContext()).isFalse();
        assertThat(received.hasProjectId()).isFalse();
        assertThat(received.hasEffortEstimateMinutes()).isFalse();
        assertThat(received.hasDeadline()).isFalse();
        assertThat(received.hasStatus()).isTrue();
        assertThat(received.getStatus()).isEqualTo(com.rnd.inbox.event.TaskStatus.TASK_STATUS_NEW);
    }

    private byte[] serialize(StructuredTaskEvent event) {
        try (KafkaProtobufSerializer<StructuredTaskEvent> serializer =
                     new KafkaProtobufSerializer<>(schemaRegistryClient, serdeConfig)) {
            return serializer.serialize(TOPIC, event);
        }
    }

    private StructuredTaskEvent deserialize(byte[] bytes) {
        try (KafkaProtobufDeserializer<StructuredTaskEvent> deserializer =
                     new KafkaProtobufDeserializer<>(schemaRegistryClient, serdeConfig, StructuredTaskEvent.class)) {
            return deserializer.deserialize(TOPIC, bytes);
        }
    }

    private static Task fullyPopulatedTask() {
        return new Task(
                "11111111-1111-1111-1111-111111111111", "proj-1",
                "Follow up with Acme", "Send the revised proposal",
                TaskStatus.NEW, 4, 0.75,
                Tier.TACTICAL, ExtractionProvenance.ETL, RoleContext.SALESPERSON,
                SourceType.CRM_DEAL, "crm:D-1",
                90, Instant.parse("2026-11-01T00:00:00Z"), null);
    }
}
