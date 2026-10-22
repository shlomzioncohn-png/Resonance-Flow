package com.rnd.chain.events;

import java.util.List;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.testsupport.PgCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.JdbcTemplate;

import com.google.protobuf.Timestamp;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.inbox.event.TaskUpdatedEvent;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializerConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for {@link TaskUpdatedEventHandler}, running the real
 * {@code @KafkaListener}/{@code @RetryableTopic} machinery against an embedded broker - same
 * pattern as {@link DependencyProposedEventHandlerIntegrationTest}, see its Javadoc for why
 * {@code mock://} schema registry URLs are used instead of a real registry process.
 */
@Tag("postgres")
@SpringBootTest
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = {
        "chain.kafka.schema-registry-url=mock://task-updated-handler-test"
})
class TaskUpdatedEventHandlerIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private com.rnd.chain.repository.TaskReadinessRepository taskReadinessRepository;

    private TaskReadinessSnapshot readiness(Task task) {
        return taskReadinessRepository.findByIds(List.of(task.getId())).get(task.getId());
    }

    private int blockingCount(Task task) {
        return readiness(task).blockingCount();
    }

    private int openConstraintCount(Task task) {
        return readiness(task).openConstraintCount();
    }

    private boolean isReady(Task task) {
        return readiness(task).isReady();
    }

    private static final String TOPIC = "task.updated";
    private static final String ERROR_TOPIC = TOPIC + ".errors";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

    @org.springframework.beans.factory.annotation.Autowired
    private TaskRepository taskRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private TaskDependencyRepository taskDependencyRepository;

    @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private KafkaTemplate<String, TaskUpdatedEvent> producer;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbcTemplate;

    private PgCleanup cleanup;

    @AfterEach
    void cleanUpRows() {
        cleanup.restore();
    }

    @BeforeEach
    void setUp() {
        cleanup = new PgCleanup(jdbcTemplate);
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaProtobufSerializer.class);
        producerProps.put(KafkaProtobufSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG,
                "mock://task-updated-handler-test");
        producer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
    }

    private static Timestamp timestamp(LocalDateTime dateTime) {
        Instant instant = dateTime.toInstant(ZoneOffset.UTC);
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static final UUID TENANT_ID = UUID.randomUUID();

    private static Task buildTask(UUID id, TaskStatus status, LocalDateTime updatedAt) {
        return Task.builder()
                .id(id).tenantId(TENANT_ID).status(status)
                .createdAt(BASE_TIME).updatedAt(updatedAt)
                .build();
    }

    /** Like {@link KafkaTestUtils#getSingleRecord}, but filtered by key - needed when the topic
     * may already carry unrelated records from an earlier test method in this same class. */
    private static ConsumerRecord<String, String> awaitRecordWithKey(
            Consumer<String, String> consumer, String key, java.time.Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(java.time.Duration.ofMillis(200))) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        return null;
    }

    @Test
    void validNewerEvent_appliesTheStatusChangeAndStampsUpdatedAt() throws InterruptedException {
        UUID taskId = UUID.randomUUID();
        taskRepository.save(buildTask(taskId, TaskStatus.PENDING, BASE_TIME));
        LocalDateTime newerTime = BASE_TIME.plusMinutes(10);

        producer.send(TOPIC, taskId.toString(), TaskUpdatedEvent.newBuilder()
                .setTaskId(taskId.toString())
                .setTenantId(TENANT_ID.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_IN_PROGRESS)
                .setUpdatedAt(timestamp(newerTime))
                .build());

        await().atMost(Duration.ofSeconds(20)).alias("status to become ACTIVE").until(() ->
                taskRepository.findById(taskId).map(t -> t.getStatus() == TaskStatus.ACTIVE).orElse(false));

        Task updated = taskRepository.findById(taskId).orElseThrow();
        assertEquals(newerTime, updated.getUpdatedAt());
    }

    @Test
    void transitioningToDone_doesNotReleaseDependents() throws InterruptedException {
        UUID blocker = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();
        taskRepository.save(buildTask(blocker, TaskStatus.ACTIVE, BASE_TIME));
        taskRepository.save(Task.builder()
                .id(dependent).tenantId(TENANT_ID).status(TaskStatus.PENDING)
                .createdAt(BASE_TIME).updatedAt(BASE_TIME).build());
        taskDependencyRepository.save(new TaskDependency(dependent, blocker));

        producer.send(TOPIC, blocker.toString(), TaskUpdatedEvent.newBuilder()
                .setTaskId(blocker.toString())
                .setTenantId(TENANT_ID.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_DONE)
                .setUpdatedAt(timestamp(BASE_TIME.plusMinutes(5)))
                .build());

        // Completion is applied only by task.completed, so a DONE update must leave everything as it was.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(TaskStatus.ACTIVE, taskRepository.findById(blocker).orElseThrow().getStatus());
            Task dependentAfter = taskRepository.findById(dependent).orElseThrow();
            assertEquals(1, blockingCount(dependentAfter));
            assertFalse(isReady(dependentAfter));
        });
    }

    @Test
    void staleEvent_withAnOlderTimestamp_isANoOp() throws InterruptedException {
        UUID taskId = UUID.randomUUID();
        taskRepository.save(buildTask(taskId, TaskStatus.ACTIVE, BASE_TIME));

        producer.send(TOPIC, taskId.toString(), TaskUpdatedEvent.newBuilder()
                .setTaskId(taskId.toString())
                .setTenantId(TENANT_ID.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_NEW)
                .setUpdatedAt(timestamp(BASE_TIME.minusMinutes(1))) // older than stored
                .build());

        // A wrongly-applied stale update would show up within this window; the state must stay unchanged throughout.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Task unchanged = taskRepository.findById(taskId).orElseThrow();
            assertEquals(TaskStatus.ACTIVE, unchanged.getStatus(), "a stale/duplicate snapshot must not overwrite a newer status");
            assertEquals(BASE_TIME, unchanged.getUpdatedAt());
        });
    }

    @Test
    void malformedEvent_goesStraightToTheErrorsTopic_neverRetried() {
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "errors-topic-probe-updated");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> errorsProbe = new DefaultKafkaConsumerFactory<>(consumerProps,
                new StringDeserializer(), new StringDeserializer()).createConsumer();
        errorsProbe.subscribe(java.util.List.of(ERROR_TOPIC));

        producer.send(TOPIC, "bad-key", TaskUpdatedEvent.newBuilder()
                .setTaskId("not-a-valid-uuid") // fails NonRetryableEventException's UUID parse
                .setTenantId(TENANT_ID.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_DONE)
                .setUpdatedAt(timestamp(BASE_TIME))
                .build());

        // PR #467 review: a generous timeout here would pass identically whether this arrived
        // immediately or only after a retry burned the handler's 2s backoff first - bounding it
        // well under that backoff is what actually proves "never retried", not just "eventually
        // reached .errors".
        ConsumerRecord<String, String> onErrorsTopic =
                KafkaTestUtils.getSingleRecord(errorsProbe, ERROR_TOPIC, java.time.Duration.ofMillis(1500));
        assertNotNull(onErrorsTopic, "a malformed event must land on .errors well under one retry backoff (2s)");
        errorsProbe.close();
    }

    @Test
    void missingTask_isCreatedFromTaskUpdatedEvent() {
        UUID taskId = UUID.randomUUID();

        producer.send(TOPIC, taskId.toString(), TaskUpdatedEvent.newBuilder()
                .setTaskId(taskId.toString())
                .setTenantId(TENANT_ID.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_IN_PROGRESS)
                .setUpdatedAt(timestamp(BASE_TIME))
                .build());

        await().atMost(Duration.ofSeconds(20)).until(() ->
                taskRepository.findById(taskId)
                        .map(t -> t.getStatus() == TaskStatus.ACTIVE)
                        .orElse(false));

        Task created = taskRepository.findById(taskId).orElseThrow();

        assertEquals(TaskStatus.ACTIVE, created.getStatus());
        assertEquals(BASE_TIME, created.getUpdatedAt());
        assertEquals(TENANT_ID, created.getTenantId());
   }

    @Test
    void missingTask_isCreatedAndDoesNotLandOnErrors() {
        UUID taskId = UUID.randomUUID();

        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "errors-topic-probe-missing-task");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        Consumer<String, String> errorsProbe =
                new DefaultKafkaConsumerFactory<>(
                        consumerProps,
                        new StringDeserializer(),
                        new StringDeserializer())
                        .createConsumer();

        errorsProbe.subscribe(java.util.List.of(ERROR_TOPIC));

        producer.send(TOPIC, taskId.toString(), TaskUpdatedEvent.newBuilder()
                .setTaskId(taskId.toString())
                .setTenantId(TENANT_ID.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_IN_PROGRESS)
                .setUpdatedAt(timestamp(BASE_TIME))
                .build());

        await().atMost(Duration.ofSeconds(20)).until(() ->
                taskRepository.findById(taskId).isPresent());

        ConsumerRecord<String, String> error =
                awaitRecordWithKey(
                        errorsProbe,
                        taskId.toString(),
                        Duration.ofSeconds(3));

        assertNull(error);

        errorsProbe.close();
     }
}
