package com.rnd.inbox.scheduling;

import com.rnd.inbox.audit.AuditLog;
import com.rnd.inbox.audit.AuditLogRepository;
import com.rnd.inbox.event.SchedulingUpdatedEvent;
import com.rnd.inbox.outbox.OutboxEvent;
import com.rnd.inbox.outbox.OutboxEventRepository;
import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskExtractionProvenance;
import com.rnd.inbox.task.repository.TaskRepository;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.repository.TenantRepository;
import com.rnd.inbox.tenant.model.TenantStatus;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

@EmbeddedKafka(
        partitions = 1,
        topics = {"task.scheduling.updated", "task.scheduling.updated.errors"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = "kafka.schema-registry.url=mock://scheduling-updated-test")
@DirtiesContext
class SchedulingConsumerIntegrationTest extends PostgresIntegrationTest {

    private static final String TOPIC = "task.scheduling.updated";
    private static final String ERROR_TOPIC = TOPIC + ".errors";
    private static final String SCHEMA_REGISTRY_URL = "mock://scheduling-updated-test";

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private KafkaTemplate<String, SchedulingUpdatedEvent> producer;
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

    private Task persistTask(boolean available, Float priorityScore) {
        Tenant tenant = new Tenant();
        tenant.setName("Acme");
        tenant.setSlug("acme-" + UUID.randomUUID());
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        Tenant saved = tenantRepository.saveAndFlush(tenant);
        createdTenantIds.add(saved.getId());

        Task task = new Task();
        task.setTenant(saved);
        task.setTitle("Scheduling target");
        task.setSourceType(SourceType.GITHUB_ISSUE);
        task.setSourceRef("github:acme/web:issue:" + UUID.randomUUID());
        task.setStatus(TaskStatus.NEW);
        task.setAvailable(available);
        task.setPriorityScore(priorityScore);
        task.setExtractionProvenance(TaskExtractionProvenance.ETL);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        return taskRepository.saveAndFlush(task);
    }

    private SchedulingUpdatedEvent event(UUID taskId, boolean available, Double priorityScore) {
        SchedulingUpdatedEvent.Builder builder = SchedulingUpdatedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(taskId.toString())
                .setAvailable(available);
        if (priorityScore != null) {
            builder.setPriorityScore(priorityScore);
        }
        return builder.build();
    }

    @Test
    void aSchedulingUpdateMaterializesBothValuesOnTheTask() {
        Task task = persistTask(false, null);

        producer.send(TOPIC, event(task.getId(), true, 7.5));

        Task updated = await(task.getId(), t -> t.isAvailable() && t.getPriorityScore() != null);
        assertThat(updated.isAvailable()).isTrue();
        assertThat(updated.getPriorityScore()).isEqualTo(7.5f);
    }

    @Test
    void anUpdateWithoutAPriorityScoreLeavesTheStoredScoreAlone() {
        Task task = persistTask(true, 4.0f);

        producer.send(TOPIC, event(task.getId(), false, null));

        Task updated = await(task.getId(), t -> !t.isAvailable());
        assertThat(updated.getPriorityScore()).isEqualTo(4.0f);
    }

    @Test
    void theLastMessageWinsRatherThanBeingDeduplicated() {
        Task task = persistTask(false, null);

        producer.send(TOPIC, event(task.getId(), true, 2.0));
        await(task.getId(), t -> t.getPriorityScore() != null);

        producer.send(TOPIC, event(task.getId(), true, 9.0));

        Task updated = await(task.getId(), t -> Float.valueOf(9.0f).equals(t.getPriorityScore()));
        assertThat(updated.getPriorityScore()).isEqualTo(9.0f);
    }

    @Test
    void reprocessingTheSameUpdateTwiceLandsOnTheSameState() {
        Task task = persistTask(false, null);
        SchedulingUpdatedEvent message = event(task.getId(), true, 6.0);

        producer.send(TOPIC, message);
        await(task.getId(), t -> t.getPriorityScore() != null);
        producer.send(TOPIC, message);
        sleep(2000);

        Task updated = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(updated.isAvailable()).isTrue();
        assertThat(updated.getPriorityScore()).isEqualTo(6.0f);
    }

    @Test
    void aSchedulingUpdateIsAuditedAndAnnouncedOnTheOutbox() {
        Task task = persistTask(false, null);

        producer.send(TOPIC, event(task.getId(), true, 3.5));
        await(task.getId(), Task::isAvailable);

        List<AuditLog> audits = auditLogRepository.findAll();
        assertThat(audits).anySatisfy(audit -> {
            assertThat(audit.getEntityId()).isEqualTo(task.getId());
            assertThat(audit.getAction()).isEqualTo("TASK_SCHEDULING_UPDATED");
        });

        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).anySatisfy(outboxEvent -> assertThat(outboxEvent.getAggregateId()).isEqualTo(task.getId()));
    }

    @Test
    void aPriorityScoreOutsideOneToTenIsRejectedWithoutTouchingTheTask() {
        Task task = persistTask(false, 5.0f);

        producer.send(TOPIC, event(task.getId(), true, 42.0));

        String error = awaitErrorContaining(task.getId().toString());
        assertThat(error).contains("VALIDATION_FAILURE");
        assertThat(error).contains("priorityScore");

        Task untouched = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(untouched.isAvailable()).isFalse();
        assertThat(untouched.getPriorityScore()).isEqualTo(5.0f);
    }

    @Test
    void anUpdateForATaskThatDoesNotExistIsRoutedAndNotRetriedForever() {
        UUID unknownTaskId = UUID.randomUUID();

        producer.send(TOPIC, event(unknownTaskId, true, 5.0));

        assertThat(awaitErrorContaining(unknownTaskId.toString())).contains("not found");
    }

    @Test
    void aMessageWithoutIsAvailableIsRejected() {
        Task task = persistTask(false, null);
        SchedulingUpdatedEvent missingAvailable = SchedulingUpdatedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(task.getId().toString())
                .build();

        producer.send(TOPIC, missingAvailable);

        assertThat(awaitErrorContaining(task.getId().toString())).contains("VALIDATION_FAILURE");
        assertThat(taskRepository.findById(task.getId()).orElseThrow().isAvailable()).isFalse();
    }

    @Test
    void corruptProtobufBytesAreRoutedToTheErrorTopicAndDoNotBlockLaterMessages() {
        rawBytesProducer.send(TOPIC, "poison-pill-key", "not a valid protobuf message".getBytes());

        assertThat(awaitErrorContaining("Malformed protobuf payload")).contains("VALIDATION_FAILURE");

        Task task = persistTask(false, null);
        producer.send(TOPIC, event(task.getId(), true, 5.0));

        Task updated = await(task.getId(), Task::isAvailable);
        assertThat(updated.isAvailable()).isTrue();
    }

    private String awaitErrorContaining(String needle) {
        long deadline = System.currentTimeMillis() + 25000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(errorConsumer, Duration.ofSeconds(2));
            for (ConsumerRecord<String, String> record : records) {
                if (record.value() != null && record.value().contains(needle)) {
                    return record.value();
                }
            }
        }
        throw new AssertionError("No error record mentioning " + needle + " arrived within the timeout");
    }

    private Task await(UUID taskId, Predicate<Task> condition) {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            Task found = taskRepository.findById(taskId).orElse(null);
            if (found != null && condition.test(found)) {
                return found;
            }
            sleep(200);
        }
        throw new AssertionError("Task " + taskId + " did not reach the expected state within the timeout");
    }

    private void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
