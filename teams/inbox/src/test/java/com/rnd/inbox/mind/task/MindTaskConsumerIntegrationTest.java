package com.rnd.inbox.mind.task;

import com.rnd.inbox.model.entity.AuditLog;
import com.rnd.inbox.model.entity.Organization;
import com.rnd.inbox.model.entity.OutboxEvent;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.enums.OrganizationStatus;
import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskExtractionProvenance;
import com.rnd.inbox.repository.AuditLogRepository;
import com.rnd.inbox.repository.OrganizationRepository;
import com.rnd.inbox.repository.OutboxEventRepository;
import com.rnd.inbox.repository.TaskRepository;
import com.rnd.inbox.support.PostgresIntegrationTest;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
@DirtiesContext
class MindTaskConsumerIntegrationTest extends PostgresIntegrationTest {

    private static final String TOPIC = "task.creation.requested";
    private static final String ERROR_TOPIC = TOPIC + ".errors";

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

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
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(errorConsumer, ERROR_TOPIC);
    }

    @AfterEach
    void tearDown() {
        errorConsumer.close();
    }

    private Organization persistOrganization() {
        Organization organization = new Organization();
        organization.setName("Acme");
        organization.setSlug("acme-" + UUID.randomUUID());
        organization.setStatus(OrganizationStatus.ACTIVE);
        organization.setCreatedAt(Instant.now());
        organization.setUpdatedAt(Instant.now());
        return organizationRepository.saveAndFlush(organization);
    }

    private String validPayload(UUID organizationId, String sourceRef, String correlationId,
            boolean hasDependencies) {
        return "{"
                + "\"eventId\":\"" + UUID.randomUUID() + "\","
                + "\"correlationId\":\"" + correlationId + "\","
                + "\"organizationId\":\"" + organizationId + "\","
                + "\"title\":\"Send the revised pricing deck to Acme\","
                + "\"sourceType\":\"SLACK\","
                + "\"sourceRef\":\"" + sourceRef + "\","
                + "\"confidence\":0.87,"
                + "\"hasDependencies\":" + hasDependencies
                + "}";
    }

    @Test
    void validEventCreatesAnMlInferredTaskWithAnAuditRecord() {
        Organization organization = persistOrganization();
        String sourceRef = "slack:T012AB3CD:C024BE91L:" + UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();

        producer.send(TOPIC, validPayload(organization.getId(), sourceRef, correlationId, false));

        Task task = awaitTask(organization.getId(), sourceRef);

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
        Organization organization = persistOrganization();
        String sourceRef = "slack:T012AB3CD:C024BE91L:" + UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();

        producer.send(TOPIC, validPayload(organization.getId(), sourceRef, correlationId, false));

        Task task = awaitTask(organization.getId(), sourceRef);

        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).anySatisfy(event -> {
            assertThat(event.getAggregateId()).isEqualTo(task.getId());
            assertThat(event.getCorrelationId()).isEqualTo(UUID.fromString(correlationId));
        });
    }

    @Test
    void aTaskWithDependenciesStartsBlockedUntilChainCalculatesAvailability() {
        Organization organization = persistOrganization();
        String sourceRef = "slack:T012AB3CD:C024BE91L:" + UUID.randomUUID();

        producer.send(TOPIC, validPayload(
                organization.getId(), sourceRef, UUID.randomUUID().toString(), true));

        assertThat(awaitTask(organization.getId(), sourceRef).isAvailable()).isFalse();
    }

    @Test
    void duplicateDeliveryDoesNotCreateASecondTask() {
        Organization organization = persistOrganization();
        String sourceRef = "slack:T012AB3CD:C024BE91L:" + UUID.randomUUID();

        producer.send(TOPIC, validPayload(
                organization.getId(), sourceRef, UUID.randomUUID().toString(), false));
        awaitTask(organization.getId(), sourceRef);

        // A different correlationId on the redelivery: idempotency keys on the source record,
        // never on the event or request id.
        producer.send(TOPIC, validPayload(
                organization.getId(), sourceRef, UUID.randomUUID().toString(), false));
        sleep(2000);

        long count = taskRepository.findAll().stream()
                .filter(t -> sourceRef.equals(t.getSourceRef()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void invalidPayloadIsRoutedToTheErrorTopicWithoutBeingSaved() {
        String sourceRef = "slack:T012AB3CD:C024BE91L:" + UUID.randomUUID();
        String missingRequiredFields = "{\"title\":\"no correlationId, confidence or hasDependencies\","
                + "\"sourceRef\":\"" + sourceRef + "\"}";

        producer.send(TOPIC, missingRequiredFields);

        ConsumerRecord<String, String> errorRecord = KafkaTestUtils.getSingleRecord(
                errorConsumer, ERROR_TOPIC, Duration.ofSeconds(10));

        assertThat(errorRecord.value()).contains("VALIDATION_FAILURE");
        assertThat(taskRepository.findAll().stream().noneMatch(t -> sourceRef.equals(t.getSourceRef()))).isTrue();
    }

    @Test
    void aStructuredSourceTypeIsAcceptedAndStillRecordedAsMlInferred() {
        Organization organization = persistOrganization();
        String sourceRef = "github:acme/web:issue:" + UUID.randomUUID();
        String payload = "{"
                + "\"eventId\":\"" + UUID.randomUUID() + "\","
                + "\"correlationId\":\"" + UUID.randomUUID() + "\","
                + "\"organizationId\":\"" + organization.getId() + "\","
                + "\"title\":\"Extracted from a thread that references a GitHub issue\","
                + "\"sourceType\":\"GITHUB_ISSUE\","
                + "\"sourceRef\":\"" + sourceRef + "\","
                + "\"confidence\":0.5,"
                + "\"hasDependencies\":false"
                + "}";

        producer.send(TOPIC, payload);

        Task task = awaitTask(organization.getId(), SourceType.GITHUB_ISSUE, sourceRef);

        // What separates this path from Bridge's is the provenance, not the source.
        assertThat(task.getExtractionProvenance()).isEqualTo(TaskExtractionProvenance.ML_INFERRED);
    }

    private Task awaitTask(UUID organizationId, String sourceRef) {
        return awaitTask(organizationId, SourceType.SLACK, sourceRef);
    }

    private Task awaitTask(UUID organizationId, SourceType sourceType, String sourceRef) {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            Optional<Task> found = taskRepository.findByOrganization_IdAndSourceTypeAndSourceRef(
                    organizationId, sourceType, sourceRef);
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
