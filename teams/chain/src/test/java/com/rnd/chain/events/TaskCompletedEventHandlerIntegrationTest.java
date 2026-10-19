package com.rnd.chain.events;

import com.rnd.chain.testsupport.PgCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.inbox.event.TaskCompletedEvent;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for {@link TaskCompletedEventHandler} against an embedded broker, same setup as
 * {@link TaskUpdatedEventHandlerIntegrationTest}. The broker stamps records with the current time.
 */
@Tag("postgres")
@SpringBootTest
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = {
        "chain.kafka.schema-registry-url=mock://task-completed-handler-test"
})
class TaskCompletedEventHandlerIntegrationTest {

    private static final String TOPIC = "task.completed";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

    @TestConfiguration
    static class Fixtures {

        @Bean
        @Primary
        RecordingSchedulingUpdatePublisher testSchedulingUpdatePublisher() {
            return new RecordingSchedulingUpdatePublisher();
        }
    }

    /** Keeps task.scheduling.updated events in memory; the listener thread writes, the test thread reads. */
    static class RecordingSchedulingUpdatePublisher implements SchedulingUpdatePublisher {

        final List<TaskSchedulingUpdatedEvent> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(TaskSchedulingUpdatedEvent event) {
            published.add(event);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private RecordingSchedulingUpdatePublisher schedulingPublisher;

    @org.springframework.beans.factory.annotation.Autowired
    private TaskRepository taskRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private TaskDependencyRepository taskDependencyRepository;

    @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private KafkaTemplate<String, TaskCompletedEvent> producer;

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
                "mock://task-completed-handler-test");
        producer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
    }

    private static Task buildTask(UUID id, TaskStatus status, LocalDateTime updatedAt, int blockingCount) {
        return Task.builder()
                .id(id).status(status).tenantId(com.rnd.chain.testsupport.TaskFixtures.DEFAULT_TENANT_ID)
                .isReady(blockingCount == 0).blockingCount(blockingCount).openConstraintCount(0)
                .createdAt(BASE_TIME).updatedAt(updatedAt)
                .build();
    }

    private void saveTask(Task task) {
        taskRepository.save(task);
    }

    private void sendCompleted(UUID taskId) {
        producer.send(TOPIC, taskId.toString(), TaskCompletedEvent.newBuilder()
                .setTaskId(taskId.toString())
                .setIsCompleted(true)
                .build());
    }

    private List<TaskSchedulingUpdatedEvent> schedulingEventsFor(UUID taskId) {
        return schedulingPublisher.published.stream().filter(e -> e.getTaskId().equals(taskId)).toList();
    }

    @Test
    void blockerCompleted_releasesTheDependent() {
        UUID blocker = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();
        saveTask(buildTask(blocker, TaskStatus.ACTIVE, BASE_TIME, 0));
        saveTask(buildTask(dependent, TaskStatus.PENDING, BASE_TIME, 1));
        taskDependencyRepository.save(new TaskDependency(dependent, blocker));

        sendCompleted(blocker);

        await().atMost(Duration.ofSeconds(20)).alias("the dependent to be released").until(() ->
                taskRepository.findById(dependent).map(Task::isReady).orElse(false));

        assertEquals(0, taskRepository.findById(dependent).orElseThrow().getBlockingCount());
        assertEquals(TaskStatus.DONE, taskRepository.findById(blocker).orElseThrow().getStatus());

        // The event is published right after the repository update, so wait for it rather than assert at once.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<TaskSchedulingUpdatedEvent> events = schedulingEventsFor(dependent);
            assertEquals(1, events.size());
            assertTrue(events.get(0).isAvailable());
        });
    }

    @Test
    void redeliveredCompleted_changesNothing() {
        UUID blocker = UUID.randomUUID();
        UUID otherBlocker = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();
        saveTask(buildTask(blocker, TaskStatus.ACTIVE, BASE_TIME, 0));
        saveTask(buildTask(otherBlocker, TaskStatus.ACTIVE, BASE_TIME, 0));
        saveTask(buildTask(dependent, TaskStatus.PENDING, BASE_TIME, 2));
        taskDependencyRepository.save(new TaskDependency(dependent, blocker));
        taskDependencyRepository.save(new TaskDependency(dependent, otherBlocker));

        sendCompleted(blocker);
        sendCompleted(blocker);

        await().atMost(Duration.ofSeconds(20)).alias("first completion to be applied").until(() ->
                taskRepository.findById(dependent).map(t -> t.getBlockingCount() == 1).orElse(false));

        // A second decrement would show up within this window; the count must stay at 1 throughout.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Task unchanged = taskRepository.findById(dependent).orElseThrow();
            assertEquals(1, unchanged.getBlockingCount());
            assertTrue(!unchanged.isReady());
            assertTrue(schedulingEventsFor(dependent).isEmpty());
        });
    }

    @Test
    void redeliveredCompleted_publishesSchedulingUpdateOnlyOnce() {
        UUID blocker = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();
        saveTask(buildTask(blocker, TaskStatus.ACTIVE, BASE_TIME, 0));
        saveTask(buildTask(dependent, TaskStatus.PENDING, BASE_TIME, 1));
        taskDependencyRepository.save(new TaskDependency(dependent, blocker));

        sendCompleted(blocker);
        sendCompleted(blocker);

        await().atMost(Duration.ofSeconds(20)).alias("the release event").until(() ->
                !schedulingEventsFor(dependent).isEmpty());

        // A second event for the redelivered record would show up within this window.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(1, schedulingEventsFor(dependent).size()));
    }

    @Test
    void completedArrivingAfterReopen_isIgnored() {
        UUID blocker = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();
        // updatedAt far in the future stands in for a reopen newer than the record's broker timestamp.
        saveTask(buildTask(blocker, TaskStatus.ACTIVE, LocalDateTime.of(2099, 1, 1, 0, 0), 0));
        saveTask(buildTask(dependent, TaskStatus.PENDING, BASE_TIME, 1));
        taskDependencyRepository.save(new TaskDependency(dependent, blocker));

        sendCompleted(blocker);

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(TaskStatus.ACTIVE, taskRepository.findById(blocker).orElseThrow().getStatus());
            assertEquals(1, taskRepository.findById(dependent).orElseThrow().getBlockingCount());
        });
    }
}
