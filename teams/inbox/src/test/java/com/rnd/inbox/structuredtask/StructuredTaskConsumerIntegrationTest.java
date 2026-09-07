package com.rnd.inbox.structuredtask;

import com.rnd.inbox.audit.AuditLog;
import com.rnd.inbox.audit.AuditLogRepository;
import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.task.SourceType;
import com.rnd.inbox.task.Task;
import com.rnd.inbox.task.TaskRepository;
import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.tenant.TenantRepository;
import com.rnd.inbox.tenant.TenantStatus;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
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

import java.nio.charset.StandardCharsets;
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
        topics = {"bridge.structured.tasks", "bridge.structured.tasks.errors"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class StructuredTaskConsumerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private KafkaTemplate<String, String> producer;
    private Consumer<String, String> errorConsumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        producer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "test-errors-" + UUID.randomUUID(), "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        errorConsumer = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new StringDeserializer()).createConsumer();
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(errorConsumer, "bridge.structured.tasks.errors");
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

    private String validPayload(UUID tenantId, SourceType sourceType, String sourceRef, String eventId) {
        return "{"
                + "\"eventId\":\"" + eventId + "\","
                + "\"tenantId\":\"" + tenantId + "\","
                + "\"title\":\"Fix flaky test\","
                + "\"sourceType\":\"" + sourceType.name() + "\","
                + "\"sourceRef\":\"" + sourceRef + "\","
                + "\"status\":\"NEW\""
                + "}";
    }

    @Test
    void validEventCreatesATaskWithAnAuditRecord() {
        Tenant tenant = persistTenant();
        String sourceRef = "github:owner/repo:issue:" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        producer.send("bridge.structured.tasks",
                validPayload(tenant.getId(), SourceType.GITHUB_ISSUE, sourceRef, eventId));

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

        producer.send("bridge.structured.tasks",
                validPayload(tenant.getId(), SourceType.CRM_DEAL, sourceRef, eventId));

        Task task = awaitTask(tenant.getId(), SourceType.CRM_DEAL, sourceRef);

        assertThat(task.getId()).isNotNull();
        assertThat(task.getSourceType()).isEqualTo(SourceType.CRM_DEAL);
    }

    @Test
    void duplicateDeliveryDoesNotCreateASecondTask() {
        Tenant tenant = persistTenant();
        String sourceRef = "github:owner/repo:issue:" + UUID.randomUUID();

        producer.send("bridge.structured.tasks", validPayload(
                tenant.getId(), SourceType.GITHUB_ISSUE, sourceRef, UUID.randomUUID().toString()));
        awaitTask(tenant.getId(), SourceType.GITHUB_ISSUE, sourceRef);

        producer.send("bridge.structured.tasks", validPayload(
                tenant.getId(), SourceType.GITHUB_ISSUE, sourceRef, UUID.randomUUID().toString()));
        sleep(2000);

        long count = taskRepository.findAll().stream()
                .filter(t -> sourceRef.equals(t.getSourceRef()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void invalidPayloadIsRoutedToTheErrorTopicWithoutBeingSaved() {
        String sourceRef = "github:owner/repo:issue:" + UUID.randomUUID();
        String invalidPayload = "{\"title\":\"missing required fields\",\"sourceRef\":\"" + sourceRef + "\"}";

        producer.send("bridge.structured.tasks", invalidPayload);

        ConsumerRecord<String, String> errorRecord = KafkaTestUtils.getSingleRecord(
                errorConsumer, "bridge.structured.tasks.errors", Duration.ofSeconds(10));

        assertThat(errorRecord.value()).contains("VALIDATION_FAILURE");
        assertThat(taskRepository.findAll().stream().noneMatch(t -> sourceRef.equals(t.getSourceRef()))).isTrue();
    }

    @Test
    void anEventPointingAtAnUnknownTenantIsRejectedInsteadOfRetried() {
        String sourceRef = "github:owner/repo:issue:" + UUID.randomUUID();

        producer.send("bridge.structured.tasks", validPayload(
                UUID.randomUUID(), SourceType.GITHUB_ISSUE, sourceRef, UUID.randomUUID().toString()));

        ConsumerRecord<String, String> error = awaitErrorContaining(sourceRef);

        assertThat(error.value()).contains("VALIDATION_FAILURE", "TENANT_NOT_FOUND", "not found");
        assertThat(header(error, "x-error-type")).isEqualTo("VALIDATION_FAILURE");
        assertThat(header(error, "x-retry-count")).isEqualTo("1");
        assertThat(taskRepository.findAll().stream().noneMatch(t -> sourceRef.equals(t.getSourceRef()))).isTrue();
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
