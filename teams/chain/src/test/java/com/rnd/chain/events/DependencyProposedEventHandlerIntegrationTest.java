package com.rnd.chain.events;

import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.testsupport.PgCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.DependencyHistoryRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.repository.TransactionRunner;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.service.DependencyEdgeService;
import com.rnd.chain.service.PriorityScoreService;
import com.rnd.chain.service.SchedulingUpdateService;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.mind.event.DependencyProposedEvent;
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
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

@Tag("postgres")
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@TestPropertySource(properties = {
        "chain.kafka.schema-registry-url=mock://dependency-proposed-handler-test"
})
class DependencyProposedEventHandlerIntegrationTest {

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

    private static final String TOPIC =
            "task.dependencies.proposed";
        private static final String ERROR_TOPIC = TOPIC + ".errors";    @org.springframework.beans.factory.annotation.Autowired
    private TaskRepository taskRepository;

    @org.springframework.beans.factory.annotation.Value(
            "${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private KafkaTemplate<String, DependencyProposedEvent> producer;

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

        Map<String, Object> producerProps =
                new HashMap<>();

        producerProps.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers);

        producerProps.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);

        producerProps.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                KafkaProtobufSerializer.class);

        producerProps.put(
                KafkaProtobufSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG,
                "mock://dependency-proposed-handler-test");

        producer =
                new KafkaTemplate<>(
                        new DefaultKafkaProducerFactory<>(
                                producerProps));
    }

    private static final UUID TENANT_ID = UUID.randomUUID();

    private static Task buildTask(UUID id) {

        return Task.builder()
                .id(id)
                .tenantId(TENANT_ID)
                .status(TaskStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void validEvent_isConsumedAndAppliedToTheDependencyGraph() {

        UUID taskId = UUID.randomUUID();
        UUID depId = UUID.randomUUID();

        taskRepository.save(buildTask(taskId));
        taskRepository.save(buildTask(depId));

        DependencyProposedEvent event =
                DependencyProposedEvent.newBuilder()
                        .setTenantId(TENANT_ID.toString())
                        .setEventId(
                                UUID.randomUUID().toString())
                        .setTaskId(
                                taskId.toString())
                        .addDependencies(
                                com.rnd.mind.event.Dependency
                                        .newBuilder()
                                        .setDependsOnTaskId(
                                                depId.toString())
                                        .build())
                        .build();

        producer.send(
                TOPIC,
                taskId.toString(),
                event);

        producer.flush();

        await()
                .atMost(Duration.ofSeconds(20))
                .alias("blockingCount to become 1")
                .until(() ->
                        taskRepository
                                .findById(taskId)
                                .map(task ->
                                        blockingCount(task) == 1)
                                .orElse(false));
    }

    @Test
    void redeliveredEventId_isAppliedOnlyOnce() {

        UUID taskId = UUID.randomUUID();
        UUID depId = UUID.randomUUID();
        String eventId =
                UUID.randomUUID().toString();

        taskRepository.save(buildTask(taskId));
        taskRepository.save(buildTask(depId));

        DependencyProposedEvent event =
                DependencyProposedEvent.newBuilder()
                        .setTenantId(TENANT_ID.toString())
                        .setEventId(eventId)
                        .setTaskId(taskId.toString())
                        .addDependencies(
                                com.rnd.mind.event.Dependency
                                        .newBuilder()
                                        .setDependsOnTaskId(
                                                depId.toString())
                                        .build())
                        .build();

        producer.send(
                TOPIC,
                taskId.toString(),
                event);

        producer.flush();

        await()
                .atMost(Duration.ofSeconds(20))
                .until(() ->
                        taskRepository
                                .findById(taskId)
                                .map(task ->
                                        blockingCount(task) == 1)
                                .orElse(false));

        producer.send(
                TOPIC,
                taskId.toString(),
                event);

        producer.flush();

        await()
                .during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(5))
                .until(() ->
                        taskRepository
                                .findById(taskId)
                                .map(task ->
                                        blockingCount(task) == 1)
                                .orElse(false));
    }
    @Test
void malformedEvent_goesStraightToTheErrorsTopic_neverRetried() {
    Map<String, Object> consumerProps = new HashMap<>();
    consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "errors-topic-probe");
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    Consumer<String, String> errorsProbe =
            new DefaultKafkaConsumerFactory<>(
                    consumerProps,
                    new StringDeserializer(),
                    new StringDeserializer())
                    .createConsumer();

    errorsProbe.subscribe(List.of(ERROR_TOPIC));

    producer.send(
            TOPIC,
            "bad-key",
            DependencyProposedEvent.newBuilder()
                        .setTenantId(TENANT_ID.toString())
                    .setEventId(UUID.randomUUID().toString())
                    .setTaskId("not-a-valid-uuid")
                    .build());

    ConsumerRecord<String, String> onErrorsTopic =
            KafkaTestUtils.getRecords(errorsProbe, Duration.ofMillis(3000)).iterator().next();

    assertNotNull(
            onErrorsTopic,
            "a malformed event must land on .errors well under one retry backoff (5s)");

    errorsProbe.close();
}

@Test
void genuinelyMalformedBytes_notValidProtobufAtAll_alsoGoesStraightToTheErrorsTopic() {
    Map<String, Object> rawProducerProps = new HashMap<>();
    rawProducerProps.put(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            bootstrapServers);
    rawProducerProps.put(
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class);
    rawProducerProps.put(
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            ByteArraySerializer.class);

    KafkaTemplate<String, byte[]> rawProducer =
            new KafkaTemplate<>(
                    new DefaultKafkaProducerFactory<>(
                            rawProducerProps));

    Map<String, Object> consumerProps = new HashMap<>();
    consumerProps.put(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            bootstrapServers);
    consumerProps.put(
            ConsumerConfig.GROUP_ID_CONFIG,
            "errors-topic-probe-raw-bytes");
    consumerProps.put(
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest");
    consumerProps.put(
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
    consumerProps.put(
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);

    Consumer<String, String> errorsProbe =
            new DefaultKafkaConsumerFactory<>(
                    consumerProps,
                    new StringDeserializer(),
                    new StringDeserializer())
                    .createConsumer();

    errorsProbe.subscribe(List.of(ERROR_TOPIC));

    rawProducer.send(
            TOPIC,
            "bad-key",
            "this is not protobuf at all, not even close"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));

    ConsumerRecord<String, String> onErrorsTopic =
            KafkaTestUtils.getRecords(errorsProbe, Duration.ofSeconds(15)).iterator().next();

    assertNotNull(
            onErrorsTopic,
            "genuinely malformed (non-protobuf) bytes must also land on .errors via ErrorHandlingDeserializer");

    errorsProbe.close();
}

    @Test
    void missingTarget_isCreatedAsPendingPlaceholderAndEdgeIsAdded() {

        UUID taskId = UUID.randomUUID();
        UUID missingDependencyId = UUID.randomUUID();

        taskRepository.save(buildTask(taskId));

        DependencyProposedEvent event =
                DependencyProposedEvent.newBuilder()
                        .setTenantId(TENANT_ID.toString())
                        .setEventId(UUID.randomUUID().toString())
                        .setTaskId(taskId.toString())
                        .addDependencies(
                                com.rnd.mind.event.Dependency
                                        .newBuilder()
                                        .setDependsOnTaskId(missingDependencyId.toString())
                                        .build())
                        .build();

        producer.send(TOPIC, taskId.toString(), event);
        producer.flush();

        await()
                .atMost(Duration.ofSeconds(10))
                .until(() ->
                        taskRepository.findById(missingDependencyId)
                                .map(task -> task.getStatus() == TaskStatus.PENDING)
                                .orElse(false)
                                && taskRepository.findById(taskId)
                                .map(task -> blockingCount(task) == 1)
                                .orElse(false));
    }
}
