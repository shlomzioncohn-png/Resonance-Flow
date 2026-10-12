package com.rnd.chain.events;

import com.google.protobuf.Timestamp;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.inbox.event.TaskCreatedEvent;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for {@link TaskCreatedEventHandler}, running the real
 * {@code @KafkaListener}/{@code @RetryableTopic} machinery against an embedded broker - same
 * pattern as {@link DependencyProposedEventHandlerIntegrationTest}, see its Javadoc for why
 * {@code mock://} schema registry URLs are used instead of a real registry process.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = {
        "chain.kafka.schema-registry-url=mock://task-created-handler-test"
})
class TaskCreatedEventHandlerIntegrationTest {

    private static final String TOPIC = "task.created";
    private static final String ERROR_TOPIC = TOPIC + ".errors";

    @TestConfiguration
    static class Fixtures {

        @Bean
        @Primary
        TaskRepository testTaskRepository() {
            return new InMemoryTaskRepository(java.util.List.of());
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private TaskRepository taskRepository;

    @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private KafkaTemplate<String, TaskCreatedEvent> producer;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaProtobufSerializer.class);
        producerProps.put(KafkaProtobufSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG,
                "mock://task-created-handler-test");
        producer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    @Test
    void validEvent_isConsumedAndInsertsANewReadyTask() throws InterruptedException {
        UUID taskId = UUID.randomUUID();

        producer.send(TOPIC, taskId.toString(), TaskCreatedEvent.newBuilder()
                .setId(taskId.toString())
                .setOwnerId("owner-1")
                .setTenantId(UUID.randomUUID().toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_NEW)
                .setCreatedAt(timestamp(Instant.now()))
                .build());

        await().atMost(Duration.ofSeconds(20)).alias("the new task to be inserted").until(() ->
                taskRepository.findById(taskId).isPresent());

        Task saved = taskRepository.findById(taskId).orElseThrow();
        assertEquals("owner-1", saved.getOwnerId());
        assertEquals(TaskStatus.PENDING, saved.getStatus());
        assertEquals(0, saved.getBlockingCount());
        assertTrue(saved.isReady());
    }

    @Test
    void redeliveredEvent_forAnExistingTask_mergesWithoutResettingEligibilityCounters() throws InterruptedException {
        UUID taskId = UUID.randomUUID();
        ((InMemoryTaskRepository) taskRepository).save(Task.builder()
                .id(taskId).ownerId("stale-owner").status(TaskStatus.PENDING)
                .isReady(false).blockingCount(2).openConstraintCount(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());

        producer.send(TOPIC, taskId.toString(), TaskCreatedEvent.newBuilder()
                .setId(taskId.toString())
                .setOwnerId("real-owner")
                .setTenantId(UUID.randomUUID().toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_NEW)
                .setCreatedAt(timestamp(Instant.now()))
                .build());

        await().atMost(Duration.ofSeconds(20)).alias("owner to be updated by the (re)delivery").until(() ->
                taskRepository.findById(taskId).map(t -> "real-owner".equals(t.getOwnerId())).orElse(false));

        Task merged = taskRepository.findById(taskId).orElseThrow();
        assertEquals(2, merged.getBlockingCount(),
                "a dependency already recorded against this task must survive a (re)delivery of task.created");
        assertEquals(1, merged.getOpenConstraintCount());
        assertFalse(merged.isReady());
    }

    @Test
    void malformedEvent_goesStraightToTheErrorsTopic_neverRetried() {
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "errors-topic-probe-created");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> errorsProbe = new DefaultKafkaConsumerFactory<>(consumerProps,
                new StringDeserializer(), new StringDeserializer()).createConsumer();
        errorsProbe.subscribe(java.util.List.of(ERROR_TOPIC));

        producer.send(TOPIC, "bad-key", TaskCreatedEvent.newBuilder()
                .setId("not-a-valid-uuid") // fails NonRetryableEventException's UUID parse
                .setOwnerId("owner-1")
                .setTenantId(UUID.randomUUID().toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_NEW)
                .setCreatedAt(timestamp(Instant.now()))
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
}
