package com.rnd.inbox.scheduling;

import com.rnd.inbox.audit.AuditLog;
import com.rnd.inbox.audit.AuditLogRepository;
import com.rnd.inbox.outbox.OutboxEvent;
import com.rnd.inbox.outbox.OutboxEventRepository;
import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.task.SourceType;
import com.rnd.inbox.task.Task;
import com.rnd.inbox.task.TaskExtractionProvenance;
import com.rnd.inbox.task.TaskRepository;
import com.rnd.inbox.task.TaskStatus;
import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.tenant.TenantRepository;
import com.rnd.inbox.tenant.TenantStatus;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

@EmbeddedKafka(
        partitions = 1,
        topics = {"task.scheduling.updated", "task.scheduling.updated.errors"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class SchedulingConsumerIntegrationTest extends PostgresIntegrationTest {

    private static final String TOPIC = "task.scheduling.updated";
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
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(errorConsumer, ERROR_TOPIC);
    }

    @AfterEach
    void tearDown() {
        errorConsumer.close();
    }

    private Task persistTask(boolean available, Float priorityScore) {
        Tenant tenant = new Tenant();
        tenant.setName("Acme");
        tenant.setSlug("acme-" + UUID.randomUUID());
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        Tenant saved = tenantRepository.saveAndFlush(tenant);

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

    private String payload(UUID taskId, boolean available, String priorityScoreJson) {
        return "{"
                + "\"eventId\":\"" + UUID.randomUUID() + "\","
                + "\"taskId\":\"" + taskId + "\","
                + "\"isAvailable\":" + available
                + (priorityScoreJson == null ? "" : ",\"priorityScore\":" + priorityScoreJson)
                + "}";
    }

    @Test
    void aSchedulingUpdateMaterializesBothValuesOnTheTask() {
        Task task = persistTask(false, null);

        producer.send(TOPIC, payload(task.getId(), true, "7.5"));

        Task updated = await(task.getId(), t -> t.isAvailable() && t.getPriorityScore() != null);
        assertThat(updated.isAvailable()).isTrue();
        assertThat(updated.getPriorityScore()).isEqualTo(7.5f);
    }

    @Test
    void anUpdateWithoutAPriorityScoreLeavesTheStoredScoreAlone() {
        Task task = persistTask(true, 4.0f);

        producer.send(TOPIC, payload(task.getId(), false, null));

        Task updated = await(task.getId(), t -> !t.isAvailable());
        assertThat(updated.getPriorityScore()).isEqualTo(4.0f);
    }

    @Test
    void theLastMessageWinsRatherThanBeingDeduplicated() {
        Task task = persistTask(false, null);

        producer.send(TOPIC, payload(task.getId(), true, "2.0"));
        await(task.getId(), t -> t.getPriorityScore() != null);

        producer.send(TOPIC, payload(task.getId(), true, "9.0"));

        Task updated = await(task.getId(), t -> Float.valueOf(9.0f).equals(t.getPriorityScore()));
        assertThat(updated.getPriorityScore()).isEqualTo(9.0f);
    }

    @Test
    void reprocessingTheSameUpdateTwiceLandsOnTheSameState() {
        Task task = persistTask(false, null);
        String message = payload(task.getId(), true, "6.0");

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

        producer.send(TOPIC, payload(task.getId(), true, "3.5"));
        await(task.getId(), Task::isAvailable);

        List<AuditLog> audits = auditLogRepository.findAll();
        assertThat(audits).anySatisfy(audit -> {
            assertThat(audit.getEntityId()).isEqualTo(task.getId());
            assertThat(audit.getAction()).isEqualTo("TASK_SCHEDULING_UPDATED");
        });

        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).anySatisfy(event -> assertThat(event.getAggregateId()).isEqualTo(task.getId()));
    }

    @Test
    void aPriorityScoreOutsideOneToTenIsRejectedWithoutTouchingTheTask() {
        Task task = persistTask(false, 5.0f);

        producer.send(TOPIC, payload(task.getId(), true, "42"));

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

        producer.send(TOPIC, payload(unknownTaskId, true, "5.0"));

        assertThat(awaitErrorContaining(unknownTaskId.toString())).contains("not found");
    }

    @Test
    void aMessageWithoutIsAvailableIsRejected() {
        Task task = persistTask(false, null);
        String missingField = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"taskId\":\"" + task.getId() + "\"}";

        producer.send(TOPIC, missingField);

        assertThat(awaitErrorContaining(task.getId().toString())).contains("VALIDATION_FAILURE");
        assertThat(taskRepository.findById(task.getId()).orElseThrow().isAvailable()).isFalse();
    }

    /**
     * Several tests here route to the same error topic, and the consumer reads it from the
     * beginning - so {@code getSingleRecord} would trip over a previous test's record. Match on
     * the id this test used instead of assuming the topic holds exactly one message.
     */
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
