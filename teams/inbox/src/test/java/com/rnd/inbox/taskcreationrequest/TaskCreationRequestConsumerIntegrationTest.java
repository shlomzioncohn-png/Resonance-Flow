package com.rnd.inbox.taskcreationrequest;

import com.rnd.inbox.audit.AuditLog;
import com.rnd.inbox.audit.AuditLogRepository;
import com.rnd.inbox.event.TaskCreationRequestedEvent;
import com.rnd.inbox.outbox.OutboxEvent;
import com.rnd.inbox.outbox.OutboxEventRepository;
import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.task.Assignment;
import com.rnd.inbox.task.AssignmentRepository;
import com.rnd.inbox.task.AssignmentRole;
import com.rnd.inbox.task.SourceType;
import com.rnd.inbox.task.Task;
import com.rnd.inbox.task.TaskExtractionProvenance;
import com.rnd.inbox.task.TaskRepository;
import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.tenant.TenantRepository;
import com.rnd.inbox.tenant.TenantStatus;
import com.rnd.inbox.user.User;
import com.rnd.inbox.user.UserRepository;
import com.rnd.inbox.user.UserRole;
import com.rnd.inbox.user.UserStatus;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

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

    private User persistUser(Tenant tenant) {
        User user = new User();
        user.setTenant(tenant);
        user.setDisplayName("Dana Cohen");
        user.setRole(UserRole.EMPLOYEE);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.saveAndFlush(user);
    }

    private TaskCreationRequestedEvent.Builder validEventBuilder(UUID tenantId, String sourceRef,
            String correlationId, boolean hasDependencies) {
        return TaskCreationRequestedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setCorrelationId(correlationId)
                .setTenantId(tenantId.toString())
                .setTitle("Send the revised pricing deck to Acme")
                .setSourceType(com.rnd.inbox.event.SourceType.SOURCE_TYPE_SLACK)
                .setSourceRef(sourceRef)
                .setConfidence(0.87)
                .setHasDependencies(hasDependencies);
    }

    private TaskCreationRequestedEvent validEvent(UUID tenantId, String sourceRef, String correlationId,
            boolean hasDependencies) {
        return validEventBuilder(tenantId, sourceRef, correlationId, hasDependencies).build();
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

        ConsumerRecord<String, String> errorRecord = awaitErrorContaining(sourceRef);

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

        ConsumerRecord<String, String> errorRecord = awaitErrorContaining(sourceRef);

        assertThat(errorRecord.value()).contains("VALIDATION_FAILURE");
        assertThat(taskRepository.findAll().stream().noneMatch(t -> sourceRef.equals(t.getSourceRef()))).isTrue();
    }

    @Test
    void anEventWithADuplicateAssigneeIsRejectedInsteadOfRetried() {
        Tenant tenant = persistTenant();
        String sourceRef = "slack:T012AB3CD:C024BE91L:" + UUID.randomUUID();
        String assigneeId = UUID.randomUUID().toString();

        TaskCreationRequestedEvent event = validEventBuilder(
                tenant.getId(), sourceRef, UUID.randomUUID().toString(), false)
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

        ConsumerRecord<String, String> errorRecord = awaitErrorContaining(sourceRef);

        assertThat(errorRecord.value()).contains("VALIDATION_FAILURE", "duplicates");
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

        ConsumerRecord<String, String> errorRecord = awaitErrorContaining(sourceRef);

        assertThat(errorRecord.value()).contains("VALIDATION_FAILURE");
        assertThat(taskRepository.findAll().stream().noneMatch(t -> sourceRef.equals(t.getSourceRef()))).isTrue();
    }

    @Test
    void anEventWithAnAssigneeCreatesTheCorrespondingAssignmentRecord() {
        Tenant tenant = persistTenant();
        User assignee = persistUser(tenant);
        String sourceRef = "slack:T012AB3CD:C024BE91L:" + UUID.randomUUID();

        TaskCreationRequestedEvent event = validEventBuilder(
                tenant.getId(), sourceRef, UUID.randomUUID().toString(), false)
                .addAssignees(com.rnd.inbox.event.Assignee.newBuilder()
                        .setAssigneeType(com.rnd.inbox.event.AssigneeType.ASSIGNEE_TYPE_USER)
                        .setId(assignee.getId().toString())
                        .setAssignmentRole(com.rnd.inbox.event.AssignmentRole.ASSIGNMENT_ROLE_OWNER))
                .build();

        producer.send(TOPIC, event);

        Task task = awaitTask(tenant.getId(), sourceRef);

        List<Assignment> assignments = assignmentRepository.findByTask_Id(task.getId());
        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).getUser().getId()).isEqualTo(assignee.getId());
        assertThat(assignments.get(0).getAssignmentRole()).isEqualTo(AssignmentRole.OWNER);
    }

    @Test
    void corruptProtobufBytesAreRoutedToTheErrorTopicAndDoNotBlockLaterMessages() {
        rawBytesProducer.send(TOPIC, "poison-pill-key", "not a valid protobuf message".getBytes());

        ConsumerRecord<String, String> errorRecord = awaitErrorContaining("Malformed protobuf payload");
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
