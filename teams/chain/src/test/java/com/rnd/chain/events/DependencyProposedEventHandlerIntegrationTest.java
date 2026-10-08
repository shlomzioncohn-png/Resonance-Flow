package com.rnd.chain.events;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.service.TaskEligibilityService;
import com.rnd.mind.event.DependencyProposedEvent;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializerConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for {@link DependencyProposedEventHandler}, running the real
 * {@code @KafkaListener}/{@code @RetryableTopic} machinery against an embedded broker - unlike
 * {@link DependencyProposedEventHandlerTest} (unit test, calls {@code onMessage} directly), this
 * confirms the actual Kafka wiring (Protobuf deserialization, retry-topic routing, the
 * {@code .errors} DLT) works, per issue #271's own acceptance criteria ("automated... tests cover
 * duplicates, retries, failures, and out-of-order events").
 *
 * Protobuf without a real Schema Registry: Confluent's serializer/deserializer normally need one
 * reachable over HTTP. {@code schema.registry.url=mock://<name>} routes both to an in-process
 * {@link io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient} instead - real
 * Protobuf wire encoding (the same magic-byte-prefixed format production uses), no network, no
 * separate registry process. Both the test's own producer and the application's real consumer
 * factories (see {@link ChainProtobufKafkaConfig}) are pointed at the same mock registry name via
 * {@code chain.kafka.schema-registry-url}, so they share one in-memory schema store.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = {
        "chain.kafka.schema-registry-url=mock://dependency-proposed-handler-test"
})
class DependencyProposedEventHandlerIntegrationTest {

    private static final String TOPIC = "task.dependencies.proposed";
    private static final String ERROR_TOPIC = TOPIC + ".errors";

    @TestConfiguration
    static class Fixtures {

        // Real in-memory repositories, but @Primary test-scoped beans so the running application
        // context's DependencyProposedEventHandler (wired via constructor injection) operates on
        // the exact same instances this test asserts against - no separate DB, no mocking of our
        // own business logic, just swapping out what the real MockDataConfig beans would be.
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

    @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private KafkaTemplate<String, DependencyProposedEvent> producer;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaProtobufSerializer.class);
        producerProps.put(KafkaProtobufSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG,
                "mock://dependency-proposed-handler-test");
        producer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
    }

    private static Task buildTask(UUID id) {
        return Task.builder()
                .id(id).ownerId("owner").status(TaskStatus.PENDING).isReady(true)
                .blockingCount(0).openConstraintCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void validEvent_isConsumedAndAppliedToTheDependencyGraph() throws InterruptedException {
        UUID taskId = UUID.randomUUID();
        UUID depId = UUID.randomUUID();
        ((InMemoryTaskRepository) taskRepository).save(buildTask(taskId));
        ((InMemoryTaskRepository) taskRepository).save(buildTask(depId));

        producer.send(TOPIC, taskId.toString(), DependencyProposedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(taskId.toString())
                .addDependencies(com.rnd.mind.event.Dependency.newBuilder().setDependsOnTaskId(depId.toString()).build())
                .build());

        await().atMost(Duration.ofSeconds(20)).alias("blockingCount to become 1").until(() ->
                taskRepository.findById(taskId).map(t -> t.getBlockingCount() == 1).orElse(false));
    }

    @Test
    void redeliveredEventId_isAppliedOnlyOnce() throws InterruptedException {
        UUID taskId = UUID.randomUUID();
        UUID depId = UUID.randomUUID();
        ((InMemoryTaskRepository) taskRepository).save(buildTask(taskId));
        ((InMemoryTaskRepository) taskRepository).save(buildTask(depId));
        String eventId = UUID.randomUUID().toString();

        DependencyProposedEvent event = DependencyProposedEvent.newBuilder()
                .setEventId(eventId)
                .setTaskId(taskId.toString())
                .addDependencies(com.rnd.mind.event.Dependency.newBuilder().setDependsOnTaskId(depId.toString()).build())
                .build();

        producer.send(TOPIC, taskId.toString(), event);
        await().atMost(Duration.ofSeconds(20)).alias("first delivery applied").until(() ->
                taskRepository.findById(taskId).map(t -> t.getBlockingCount() == 1).orElse(false));

        producer.send(TOPIC, taskId.toString(), event); // exact same event_id, redelivered
        // A wrongly-duplicated apply would show up within this window; the count must stay at 1 throughout.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(1, taskRepository.findById(taskId).orElseThrow().getBlockingCount(),
                        "redelivery of the same event_id must not double-apply"));
    }

    @Test
    void malformedEvent_goesStraightToTheErrorsTopic_neverRetried() {
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "errors-topic-probe");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> errorsProbe = new DefaultKafkaConsumerFactory<>(consumerProps,
                new StringDeserializer(), new StringDeserializer()).createConsumer();
        errorsProbe.subscribe(java.util.List.of(ERROR_TOPIC));

        producer.send(TOPIC, "bad-key", DependencyProposedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId("not-a-valid-uuid") // fails NonRetryableEventException's UUID parse
                .build());

        // PR #467 review: a generous timeout here would pass identically whether this arrived
        // immediately or only after a retry burned the handler's 5s backoff first - bounding it
        // well under that backoff is what actually proves "never retried", not just "eventually
        // reached .errors".
        ConsumerRecord<String, String> onErrorsTopic =
                KafkaTestUtils.getSingleRecord(errorsProbe, ERROR_TOPIC, java.time.Duration.ofMillis(3000));
        assertNotNull(onErrorsTopic, "a malformed event must land on .errors well under one retry backoff (5s)");
        errorsProbe.close();
    }

    @Test
    void genuinelyMalformedBytes_notValidProtobufAtAll_alsoGoesStraightToTheErrorsTopic() {
        // PR #467 review: every "malformed" test above sends well-formed Protobuf with an
        // invalid field value - an application-level failure (parseUuid), not a deserialization
        // failure. ChainProtobufKafkaConfig's own Javadoc makes a specific claim that
        // ErrorHandlingDeserializer routes wrong-schema/corrupt bytes through the same error
        // path - this sends bytes that aren't Confluent-wire-format Protobuf at all (no magic
        // byte/schema-id prefix) to actually exercise that claim.
        Map<String, Object> rawProducerProps = new HashMap<>();
        rawProducerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        rawProducerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        rawProducerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        KafkaTemplate<String, byte[]> rawProducer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(rawProducerProps));

        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "errors-topic-probe-raw-bytes");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> errorsProbe = new DefaultKafkaConsumerFactory<>(consumerProps,
                new StringDeserializer(), new StringDeserializer()).createConsumer();
        errorsProbe.subscribe(java.util.List.of(ERROR_TOPIC));

        rawProducer.send(TOPIC, "bad-key",
                "this is not protobuf at all, not even close".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ConsumerRecord<String, String> onErrorsTopic =
                KafkaTestUtils.getSingleRecord(errorsProbe, ERROR_TOPIC, java.time.Duration.ofSeconds(15));
        assertNotNull(onErrorsTopic,
                "genuinely malformed (non-protobuf) bytes must also land on .errors via ErrorHandlingDeserializer");
        errorsProbe.close();
    }

    @Test
    void outOfOrderEvent_succeedsOnceTheReferencedTaskShowsUpDuringTheRetryWindow() throws InterruptedException {
        UUID taskId = UUID.randomUUID();
        UUID depId = UUID.randomUUID(); // does NOT exist yet when the event is first published
        ((InMemoryTaskRepository) taskRepository).save(buildTask(taskId));

        producer.send(TOPIC, taskId.toString(), DependencyProposedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(taskId.toString())
                .addDependencies(com.rnd.mind.event.Dependency.newBuilder().setDependsOnTaskId(depId.toString()).build())
                .build());

        // Give the first attempt a moment to fail with ReferencedTaskNotFoundException, then let
        // the referenced task "arrive" mid-retry-window - simulating task.created landing late,
        // same race @271's interim retry-based design (see DependencyProposedEventHandler's class
        // Javadoc) is meant to tolerate.
        Thread.sleep(1500);
        ((InMemoryTaskRepository) taskRepository).save(buildTask(depId));

        await().atMost(Duration.ofSeconds(20)).alias("a later retry attempt to succeed now that the task exists").until(() ->
                taskRepository.findById(taskId).map(t -> t.getBlockingCount() == 1).orElse(false));
    }
}
