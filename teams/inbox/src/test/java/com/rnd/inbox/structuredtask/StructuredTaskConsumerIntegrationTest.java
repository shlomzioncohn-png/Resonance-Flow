package com.rnd.inbox.structuredtask;

import com.rnd.inbox.audit.AuditLog;
import com.rnd.inbox.audit.AuditLogRepository;
import com.rnd.inbox.event.StructuredTaskEvent;
import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.repository.TaskRepository;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.repository.TenantRepository;
import com.rnd.inbox.tenant.model.TenantStatus;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EmbeddedKafka(
        partitions = 1,
        topics = {"bridge.structured.tasks", "bridge.structured.tasks.errors"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = "kafka.schema-registry.url=mock://structured-task-test")
@DirtiesContext
class StructuredTaskConsumerIntegrationTest extends PostgresIntegrationTest {

    private static final String TOPIC = "bridge.structured.tasks";
    private static final String ERROR_TOPIC = TOPIC + ".errors";
    private static final String SCHEMA_REGISTRY_URL = "mock://structured-task-test";

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private KafkaTemplate<String, StructuredTaskEvent> producer;
    private KafkaTemplate<String, byte[]> rawBytesProducer;
    private Consumer<String, String> errorConsumer;
    private final List<UUID> createdTenantIds = new ArrayList<>();

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
        for (UUID tenantId : createdTenantIds) {
            jdbcTemplate.update(
                    "DELETE FROM audit_log WHERE entity_id IN (SELECT id FROM tasks WHERE tenant_id = ?)", tenantId);
            jdbcTemplate.update(
                    "DELETE FROM outbox WHERE aggregate_id IN (SELECT id FROM tasks WHERE tenant_id = ?)", tenantId);
            jdbcTemplate.update("DELETE FROM tenants WHERE id = ?", tenantId);
        }
    }

    private Tenant persistTenant() {
        Tenant tenant = new Tenant();
        tenant.setName("Acme");
        tenant.setSlug("acme-" + UUID.randomUUID());
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        Tenant saved = tenantRepository.saveAndFlush(tenant);
        createdTenantIds.add(saved.getId());
        return saved;
    }

    private StructuredTaskEvent.Builder validEventBuilder(UUID tenantId, com.rnd.inbox.event.SourceType sourceType,
            String sourceRef, String eventId) {
        return StructuredTaskEvent.newBuilder()
                .setEventId(eventId)
                .setTenantId(tenantId.toString())
                .setTitle("Fix flaky test")
                .setSourceType(sourceType)
                .setSourceRef(sourceRef)
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_NEW);
    }

    private StructuredTaskEvent validEvent(UUID tenantId, com.rnd.inbox.event.SourceType sourceType,
            String sourceRef, String eventId) {
        return validEventBuilder(tenantId, sourceType, sourceRef, eventId).build();
    }

    @Test
    void validEventCreatesATaskWithAnAuditRecord() {
        Tenant tenant = persistTenant();
        String sourceRef = "github:owner/repo:issue:" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        producer.send(TOPIC, validEvent(tenant.getId(),
                com.rnd.inbox.event.SourceType.SOURCE_TYPE_GITHUB_ISSUE, sourceRef, eventId));

        Task task = awaitTask(tenant.getId(), SourceType.GITHUB_ISSUE, sourceRef);

        assertThat(task.getId()).isNotNull();
        assertThat(task.getTitle()).isEqualTo("Fix flaky test");

        List<AuditLog> audits = auditLogRepository.findAll();
        assertThat(audits).anySatisfy(audit -> {
            assertThat(audit.getEntityId()).isEqualTo(task.getId());
            assertThat(audit.getAction()).isEqualTo("TASK_CREATED");
            assertThat(audit.getCorrelationId()).isEqualTo(UUID.fromString(eventId));
        });
    }

    @Test
    void tasksFromANonGithubSourceGoThroughTheSameConsumer() {
        Tenant tenant = persistTenant();
        String sourceRef = "crm:deal:" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        producer.send(TOPIC, validEvent(tenant.getId(),
                com.rnd.inbox.event.SourceType.SOURCE_TYPE_CRM_DEAL, sourceRef, eventId));

        Task task = awaitTask(tenant.getId(), SourceType.CRM_DEAL, sourceRef);

        assertThat(task.getId()).isNotNull();
        assertThat(task.getSourceType()).isEqualTo(SourceType.CRM_DEAL);
    }

    @Test
    void duplicateDeliveryDoesNotCreateASecondTask() {
        Tenant tenant = persistTenant();
        String sourceRef = "github:owner/repo:issue:" + UUID.randomUUID();

        producer.send(TOPIC, validEvent(tenant.getId(), com.rnd.inbox.event.SourceType.SOURCE_TYPE_GITHUB_ISSUE,
                sourceRef, UUID.randomUUID().toString()));
        awaitTask(tenant.getId(), SourceType.GITHUB_ISSUE, sourceRef);

        producer.send(TOPIC, validEvent(tenant.getId(), com.rnd.inbox.event.SourceType.SOURCE_TYPE_GITHUB_ISSUE,
                sourceRef, UUID.randomUUID().toString()));
        sleep(2000);

        long count = taskRepository.findAll().stream()
                .filter(t -> sourceRef.equals(t.getSourceRef()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void invalidPayloadIsRoutedToTheErrorTopicWithoutBeingSaved() {
        String sourceRef = "github:owner/repo:issue:" + UUID.randomUUID();
        StructuredTaskEvent missingRequiredFields = StructuredTaskEvent.newBuilder()
                .setTitle("missing required fields")
                .setSourceRef(sourceRef)
                .build();

        producer.send(TOPIC, missingRequiredFields);

        ConsumerRecord<String, String> errorRecord = awaitErrorContaining(sourceRef);

        assertThat(errorRecord.value()).contains("VALIDATION_FAILURE");
        assertThat(taskRepository.findAll().stream().noneMatch(t -> sourceRef.equals(t.getSourceRef()))).isTrue();
    }

    @Test
    void anEventWithADuplicateAssigneeIsRejectedInsteadOfRetried() {
        Tenant tenant = persistTenant();
        String sourceRef = "github:owner/repo:issue:" + UUID.randomUUID();
        String assigneeId = UUID.randomUUID().toString();

        StructuredTaskEvent event = validEventBuilder(tenant.getId(),
                com.rnd.inbox.event.SourceType.SOURCE_TYPE_GITHUB_ISSUE, sourceRef, UUID.randomUUID().toString())
                .addAssignees(com.rnd.inbox.event.Assignee.newBuilder()
                        .setAssigneeType(com.rnd.inbox.event.AssigneeType.ASSIGNEE_TYPE_USER)
                        .setId(assigneeId)
                        .setAssignmentRole(com.rnd.inbox.event.AssignmentRole.ASSIGNMENT_ROLE_OWNER))
                .addAssignees(com.rnd.inbox.event.Assignee.newBuilder()
                        .setAssigneeType(com.rnd.inbox.event.AssigneeType.ASSIGNEE_TYPE_USER)
                        .setId(assigneeId)
                        .setAssignmentRole(com.rnd.inbox.event.AssignmentRole.ASSIGNMENT_ROLE_COLLABORATOR))
                .build();

        producer.send(TOPIC, event);

        ConsumerRecord<String, String> error = awaitErrorContaining(sourceRef);

        assertThat(error.value()).contains("VALIDATION_FAILURE", "duplicates");
        assertThat(header(error, "x-error-type")).isEqualTo("VALIDATION_FAILURE");
        assertThat(header(error, "x-retry-count")).isEqualTo("0");
        assertThat(taskRepository.findAll().stream().noneMatch(t -> sourceRef.equals(t.getSourceRef()))).isTrue();
    }

    @Test
    void anEventPointingAtAnUnknownTenantIsRejectedInsteadOfRetried() {
        String sourceRef = "github:owner/repo:issue:" + UUID.randomUUID();

        producer.send(TOPIC, validEvent(UUID.randomUUID(), com.rnd.inbox.event.SourceType.SOURCE_TYPE_GITHUB_ISSUE,
                sourceRef, UUID.randomUUID().toString()));

        ConsumerRecord<String, String> error = awaitErrorContaining(sourceRef);

        assertThat(error.value()).contains("VALIDATION_FAILURE", "TENANT_NOT_FOUND", "not found");
        assertThat(header(error, "x-error-type")).isEqualTo("VALIDATION_FAILURE");
        assertThat(header(error, "x-retry-count")).isEqualTo("1");
        assertThat(taskRepository.findAll().stream().noneMatch(t -> sourceRef.equals(t.getSourceRef()))).isTrue();
    }

    @Test
    void corruptProtobufBytesAreRoutedToTheErrorTopicAndDoNotBlockLaterMessages() {
        rawBytesProducer.send(TOPIC, "poison-pill-key", "not a valid protobuf message".getBytes());

        ConsumerRecord<String, String> errorRecord = awaitErrorContaining("Malformed protobuf payload");
        assertThat(errorRecord.value()).contains("VALIDATION_FAILURE");

        Tenant tenant = persistTenant();
        String sourceRef = "github:owner/repo:issue:" + UUID.randomUUID();
        producer.send(TOPIC, validEvent(tenant.getId(), com.rnd.inbox.event.SourceType.SOURCE_TYPE_GITHUB_ISSUE,
                sourceRef, UUID.randomUUID().toString()));

        assertThat(awaitTask(tenant.getId(), SourceType.GITHUB_ISSUE, sourceRef)).isNotNull();
    }

    private String header(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * Several tests here route to the same error topic, and the consumer reads it from the
     * beginning - so {@code getSingleRecord} would trip over a previous test's record. Match on
     * the ref this test used instead of assuming the topic holds exactly one message.
     */
    private ConsumerRecord<String, String> awaitErrorContaining(String needle) {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(errorConsumer, Duration.ofSeconds(2));
            for (ConsumerRecord<String, String> record : records) {
                if (record.value() != null && record.value().contains(needle)) {
                    return record;
                }
            }
        }
        throw new AssertionError("No error record mentioning " + needle + " arrived within the timeout");
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
