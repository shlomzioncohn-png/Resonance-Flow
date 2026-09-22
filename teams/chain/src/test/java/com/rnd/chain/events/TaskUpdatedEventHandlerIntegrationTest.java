package com.rnd.chain.events;

import com.google.protobuf.Timestamp;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for {@link TaskUpdatedEventHandler}, running the real
 * {@code @KafkaListener}/{@code @RetryableTopic} machinery against an embedded broker - same
 * pattern as {@link DependencyProposedEventHandlerIntegrationTest}, see its Javadoc for why
 * {@code mock://} schema registry URLs are used instead of a real registry process.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = {
        "chain.kafka.schema-registry-url=mock://task-updated-handler-test"
})
class TaskUpdatedEventHandlerIntegrationTest {

    private static final String TOPIC = "task.updated";
    private static final String ERROR_TOPIC = TOPIC + ".errors";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

    @TestConfiguration
    static class Fixtures {

        @Bean
        @Primary
        TaskRepository testTaskRepository() {
            return new InMemoryTaskRepository(java.util.List.of());
        }

        @Bean
        @Primary
        TaskDependencyRepository testTaskDependencyRepository() {
            return new InMemoryTaskDependencyRepository(java.util.List.of());
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private TaskRepository taskRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private TaskDependencyRepository taskDependencyRepository;

    @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private KafkaTemplate<String, TaskUpdatedEvent> producer;

    @BeforeEach
    void setUp() {
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

    private static Task buildTask(UUID id, TaskStatus status, LocalDateTime updatedAt) {
        return Task.builder()
                .id(id).ownerId("owner").status(status).isReady(true)
                .blockingCount(0).openConstraintCount(0)
                .createdAt(BASE_TIME).updatedAt(updatedAt)
                .build();
    }

    private void awaitUntil(String description, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        fail("Timed out waiting for: " + description);
    }

    @Test
    void validNewerEvent_appliesTheStatusChangeAndStampsUpdatedAt() throws InterruptedException {
        UUID taskId = UUID.randomUUID();
        ((InMemoryTaskRepository) taskRepository).save(buildTask(taskId, TaskStatus.PENDING, BASE_TIME));
        LocalDateTime newerTime = BASE_TIME.plusMinutes(10);

        producer.send(TOPIC, taskId.toString(), TaskUpdatedEvent.newBuilder()
                .setTaskId(taskId.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_IN_PROGRESS)
                .setUpdatedAt(timestamp(newerTime))
                .build());

        awaitUntil("status to become ACTIVE", () ->
                taskRepository.findById(taskId).map(t -> t.getStatus() == TaskStatus.ACTIVE).orElse(false));

        Task updated = taskRepository.findById(taskId).orElseThrow();
        assertEquals(newerTime, updated.getUpdatedAt());
    }

    @Test
    void transitioningToDone_releasesDependents() throws InterruptedException {
        UUID blocker = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();
        ((InMemoryTaskRepository) taskRepository).save(buildTask(blocker, TaskStatus.ACTIVE, BASE_TIME));
        ((InMemoryTaskRepository) taskRepository).save(Task.builder()
                .id(dependent).ownerId("owner").status(TaskStatus.PENDING)
                .isReady(false).blockingCount(1).openConstraintCount(0)
                .createdAt(BASE_TIME).updatedAt(BASE_TIME).build());
        ((InMemoryTaskDependencyRepository) taskDependencyRepository).save(new TaskDependency(dependent, blocker));

        producer.send(TOPIC, blocker.toString(), TaskUpdatedEvent.newBuilder()
                .setTaskId(blocker.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_DONE)
                .setUpdatedAt(timestamp(BASE_TIME.plusMinutes(5)))
                .build());

        awaitUntil("the dependent to be released", () ->
                taskRepository.findById(dependent).map(Task::isReady).orElse(false));

        assertEquals(0, taskRepository.findById(dependent).orElseThrow().getBlockingCount());
    }

    @Test
    void staleEvent_withAnOlderTimestamp_isANoOp() throws InterruptedException {
        UUID taskId = UUID.randomUUID();
        ((InMemoryTaskRepository) taskRepository).save(buildTask(taskId, TaskStatus.ACTIVE, BASE_TIME));

        producer.send(TOPIC, taskId.toString(), TaskUpdatedEvent.newBuilder()
                .setTaskId(taskId.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_DONE)
                .setUpdatedAt(timestamp(BASE_TIME.minusMinutes(1))) // older than stored
                .build());

        // Give a wrongly-applied stale update a chance to happen, then confirm it didn't.
        Thread.sleep(3000);

        Task unchanged = taskRepository.findById(taskId).orElseThrow();
        assertEquals(TaskStatus.ACTIVE, unchanged.getStatus(), "a stale/duplicate snapshot must not overwrite a newer status");
        assertEquals(BASE_TIME, unchanged.getUpdatedAt());
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
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_DONE)
                .setUpdatedAt(timestamp(BASE_TIME))
                .build());

        ConsumerRecord<String, String> onErrorsTopic =
                KafkaTestUtils.getSingleRecord(errorsProbe, ERROR_TOPIC, java.time.Duration.ofSeconds(15));
        assertNotNull(onErrorsTopic, "a malformed event must land on the .errors topic");
        errorsProbe.close();
    }

    @Test
    void outOfOrderEvent_succeedsOnceTheReferencedTaskShowsUpDuringTheRetryWindow() throws InterruptedException {
        UUID taskId = UUID.randomUUID(); // does NOT exist yet when the event is first published

        producer.send(TOPIC, taskId.toString(), TaskUpdatedEvent.newBuilder()
                .setTaskId(taskId.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_IN_PROGRESS)
                .setUpdatedAt(timestamp(BASE_TIME))
                .build());

        // Give the first attempt a moment to fail with ReferencedTaskNotFoundException, then let
        // the task "arrive" (simulating task.created landing late) mid-retry-window - same race
        // @271's interim retry-based design is meant to tolerate (see TaskUpdatedEventHandler's
        // class Javadoc / the implementation plan's flagged concern about this window's length).
        Thread.sleep(500);
        ((InMemoryTaskRepository) taskRepository).save(buildTask(taskId, TaskStatus.PENDING, BASE_TIME.minusMinutes(1)));

        awaitUntil("a later retry attempt to succeed now that the task exists", () ->
                taskRepository.findById(taskId).map(t -> t.getStatus() == TaskStatus.ACTIVE).orElse(false));
    }
}
