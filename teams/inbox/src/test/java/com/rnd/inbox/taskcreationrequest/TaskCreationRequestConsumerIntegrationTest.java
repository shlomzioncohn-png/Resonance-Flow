package com.rnd.inbox.taskcreationrequest;

import com.rnd.inbox.audit.AuditLog;
import com.rnd.inbox.audit.AuditLogRepository;
import com.rnd.inbox.event.TaskCreationRequestedEvent;
import com.rnd.inbox.outbox.OutboxEvent;
import com.rnd.inbox.outbox.OutboxEventRepository;
import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.task.SourceType;
import com.rnd.inbox.task.Task;
import com.rnd.inbox.task.TaskExtractionProvenance;
import com.rnd.inbox.task.TaskRepository;
import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.tenant.TenantRepository;
import com.rnd.inbox.tenant.TenantStatus;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EmbeddedKafka(
        partitions = 1,
        topics = {"task.creation.requested", "task.creation.requested.errors"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = "kafka.schema-registry.url=mock://task-creation-requested-test")
@DirtiesContext
class TaskCreationRequestConsumerIntegrationTest extends PostgresIntegrationTest {

    private static final String TOPIC = "task.creation.requested";
    private static final String ERROR_TOPIC = TOPIC + ".errors";
    private static final String SCHEMA_REGISTRY_URL = "mock://task-creation-requested-test";

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private KafkaTemplate<String, TaskCreationRequestedEvent> producer;
    private KafkaTemplate<String, byte[]> rawBytesProducer;
    private Consumer<String, String> errorConsumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        producerProps.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, SCHEMA_REGISTRY_URL);
        producerProps.put(AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, true);
        producer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                producerProps, new StringSerializer(), new KafkaProtobufSerializer<>()));

        rawBytesProducer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                KafkaTestUtils.producerProps(embeddedKafkaBroker), new StringSerializer(), new ByteArraySerializer()));

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "test-errors-" + UUID.randomUUID(), "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        errorConsumer = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new StringDeserializer()).createConsumer();
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(errorConsumer, ERROR_TOPIC);
    }

    @AfterEach
    void tearDown() {
        errorConsumer.close();
    }

    private Tenant persistTenant() {
        Tenant tenant = new Tenant();
        tenant.setName("Acme");
        tenant.setSlug("acme-" + UUID.randomUUID());
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        return tenantRepository.saveAndFlush(tenant);
    }

    private TaskCreationRequestedEvent validEvent(UUID tenantId, String sourceRef, String correlationId,
            boolean hasDependencies) {
        return TaskCreationRequestedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setCorrelationId(correlationId)
                .setTenantId(tenantId.toString())
                .setTitle("Send the revised pricing deck to Acme")
                .setSourceType(com.rnd.inbox.event.SourceType.SOURCE_TYPE_SLACK)
                .setSourceRef(sourceRef)
                .setConfidence(0.87)
                .setHasDependencies(hasDependencies)
                .build();
    }

    @Test
    void validEventCreatesAnMlInferredTaskWithAnAuditRecord() {
        Tenant tenant = persistTenant();
        String sourceRef = "slack:T012AB3CD:C024BE91L:" + UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();

        producer.send(TOPIC, validEvent(tenant.getId(), sourceRef, correlationId, false));

        Task task = awaitTask(tenant.getId(), sourceRef);

        assertThat(task.getId()).isNotNull();
        assertThat(task.getTitle()).isEqualTo("Send the revised pricing deck to Acme");
        assertThat(task.getExtractionProvenance()).isEqualTo(TaskExtractionProvenance.ML_INFERRED);
        assertThat(task.getConfidence()).isEqualTo(0.87f);
        assertThat(task.isAvailable()).isTrue();

        List<AuditLog> audits = auditLogRepository.findAll();
        assertThat(audits).anySatisfy(audit -> {
            assertThat(audit.getEntityId()).isEqualTo(task.getId());
            assertThat(audit.getAction()).isEqualTo("TASK_CREATED");
            assertThat(audit.getCorrelationId()).isEqualTo(UUID.fromString(correlationId));
        });
    }

    @Test
    void mindsCorrelationIdRidesOnTheOutboxRowSoItComesBackOnTaskCreated() {
        Tenant tenant = persistTenant();
        String sourceRef = "slack:T012AB3CD:C024BE91L:" + UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();

        producer.send(TOPIC, validEvent(tenant.getId(), sourceRef, correlationId, false));

        Task task = awaitTask(tenant.getId(), sourceRef);

        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).anySatisfy(event -> {
            assertThat(event.getAggregateId()).isEqualTo(task.getId());
            assertThat(event.getCorrelationId()).isEqualTo(UUID.fromString(correlationId));
        });
    }

    @Test
    void aTaskWithDependenciesStartsBlockedUntilChainCalculatesAvailability() {
        Tenant tenant = persistTenant();
        String sourceRef = "slack:T012AB3CD:C024BE91L:" + UUID.randomUUID();

        producer.send(TOPIC, validEvent(tenant.getId(), sourceRef, UUID.randomUUID().toString(), true));

        assertThat(awaitTask(tenant.getId(), sourceRef).isAvailable()).isFalse();
    }

    @Test
    void duplicateDeliveryDoesNotCreateASecondTask() {
        Tenant tenant = persistTenant();
        String sourceRef = "slack:T012AB3CD:C024BE91L:" + UUID.randomUUID();

        producer.send(TOPIC, validEvent(tenant.getId(), sourceRef, UUID.randomUUID().toString(), false));
        awaitTask(tenant.getId(), sourceRef);

        producer.send(TOPIC, validEvent(tenant.getId(), sourceRef, UUID.randomUUID().toString(), false));
        sleep(2000);

        long count = taskRepository.findAll().stream()
                .filter(t -> sourceRef.equals(t.getSourceRef()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void invalidPayloadFailsValidationAndIsRoutedToTheErrorTopicWithoutBeingSaved() {
        String sourceRef = "slack:T012AB3CD:C024BE91L:" + UUID.randomUUID();
        TaskCreationRequestedEvent missingRequiredFields = TaskCreationRequestedEvent.newBuilder()
                .setTitle("no correlationId or tenantId")
                .setSourceType(com.rnd.inbox.event.SourceType.SOURCE_TYPE_SLACK)
                .setSourceRef(sourceRef)
                .build();

        producer.send(TOPIC, missingRequiredFields);

        ConsumerRecord<String, String> errorRecord = awaitErrorRecordContaining("failed validation");

        assertThat(errorRecord.value()).contains("VALIDATION_FAILURE");
        assertThat(taskRepository.findAll().stream().noneMatch(t -> sourceRef.equals(t.getSourceRef()))).isTrue();
    }

    @Test
    void anEventWithoutConfidenceFailsValidationAndIsRoutedToTheErrorTopicWithoutBeingSaved() {
        Tenant tenant = persistTenant();
        String sourceRef = "slack:T012AB3CD:C024BE91L:" + UUID.randomUUID();
        TaskCreationRequestedEvent missingConfidence = TaskCreationRequestedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setCorrelationId(UUID.randomUUID().toString())
                .setTenantId(tenant.getId().toString())
                .setTitle("Send the revised pricing deck to Acme")
                .setSourceType(com.rnd.inbox.event.SourceType.SOURCE_TYPE_SLACK)
                .setSourceRef(sourceRef)
                .setHasDependencies(false)
                .build();

        producer.send(TOPIC, missingConfidence);

        ConsumerRecord<String, String> errorRecord = awaitErrorRecordContaining("failed validation");

        assertThat(errorRecord.value()).contains("VALIDATION_FAILURE");
        assertThat(taskRepository.findAll().stream().noneMatch(t -> sourceRef.equals(t.getSourceRef()))).isTrue();
    }

    @Test
    void anEventWithoutHasDependenciesFailsValidationAndIsRoutedToTheErrorTopicWithoutBeingSaved() {
        Tenant tenant = persistTenant();
        String sourceRef = "slack:T012AB3CD:C024BE91L:" + UUID.randomUUID();
        TaskCreationRequestedEvent missingHasDependencies = TaskCreationRequestedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setCorrelationId(UUID.randomUUID().toString())
                .setTenantId(tenant.getId().toString())
                .setTitle("Send the revised pricing deck to Acme")
                .setSourceType(com.rnd.inbox.event.SourceType.SOURCE_TYPE_SLACK)
                .setSourceRef(sourceRef)
                .setConfidence(0.87)
                .build();

        producer.send(TOPIC, missingHasDependencies);

        ConsumerRecord<String, String> errorRecord = awaitErrorRecordContaining("failed validation");

        assertThat(errorRecord.value()).contains("VALIDATION_FAILURE");
        assertThat(taskRepository.findAll().stream().noneMatch(t -> sourceRef.equals(t.getSourceRef()))).isTrue();
    }

    @Test
    void corruptProtobufBytesAreRoutedToTheErrorTopicAndDoNotBlockLaterMessages() {
        rawBytesProducer.send(TOPIC, "poison-pill-key", "not a valid protobuf message".getBytes());

        ConsumerRecord<String, String> errorRecord = awaitErrorRecordContaining("Malformed protobuf payload");
        assertThat(errorRecord.value()).contains("VALIDATION_FAILURE");

        Tenant tenant = persistTenant();
        String sourceRef = "slack:T012AB3CD:C024BE91L:" + UUID.randomUUID();
        producer.send(TOPIC, validEvent(tenant.getId(), sourceRef, UUID.randomUUID().toString(), false));

        assertThat(awaitTask(tenant.getId(), sourceRef)).isNotNull();
    }

    @Test
    void aStructuredSourceTypeIsAcceptedAndStillRecordedAsMlInferred() {
        Tenant tenant = persistTenant();
        String sourceRef = "github:acme/web:issue:" + UUID.randomUUID();
        TaskCreationRequestedEvent event = TaskCreationRequestedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setCorrelationId(UUID.randomUUID().toString())
                .setTenantId(tenant.getId().toString())
                .setTitle("Extracted from a thread that references a GitHub issue")
                .setSourceType(com.rnd.inbox.event.SourceType.SOURCE_TYPE_GITHUB_ISSUE)
                .setSourceRef(sourceRef)
                .setConfidence(0.5)
                .setHasDependencies(false)
                .build();

        producer.send(TOPIC, event);

        Task task = awaitTask(tenant.getId(), SourceType.GITHUB_ISSUE, sourceRef);

        assertThat(task.getExtractionProvenance()).isEqualTo(TaskExtractionProvenance.ML_INFERRED);
    }

    private ConsumerRecord<String, String> awaitErrorRecordContaining(String needle) {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record
                    : KafkaTestUtils.getRecords(errorConsumer, Duration.ofSeconds(1))) {
                if (record.value().contains(needle)) {
                    return record;
                }
            }
        }
        throw new AssertionError("No record containing \"" + needle + "\" appeared on " + ERROR_TOPIC);
    }

    private Task awaitTask(UUID tenantId, String sourceRef) {
        return awaitTask(tenantId, SourceType.SLACK, sourceRef);
    }

    private Task awaitTask(UUID tenantId, SourceType sourceType, String sourceRef) {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            Optional<Task> found = taskRepository.findByTenant_IdAndSourceTypeAndSourceRef(
                    tenantId, sourceType, sourceRef);
            if (found.isPresent()) {
                return found.get();
            }
            sleep(200);
        }
        throw new AssertionError("Task for sourceRef " + sourceRef + " was not created within the timeout");
    }

    private void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
