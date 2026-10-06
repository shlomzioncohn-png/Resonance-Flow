package pipe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// design doc §09 integration tests
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = "resonance.task-notifications", bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class PipeKafkaToWebSocketIntegrationTest {

    private static final String TOPIC = "resonance.task-notifications";
    private static final String SCHEMA_REGISTRY_URL = "mock://relay-pipe-it-" + UUID.randomUUID();
    private static final String ORG_ID = "org-1";
    private static final String USER_ID = "user-1";

    @DynamicPropertySource
    static void schemaRegistry(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.properties.schema.registry.url", () -> SCHEMA_REGISTRY_URL);
    }

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StandardWebSocketClient webSocketClient = new StandardWebSocketClient();

    private KafkaProducer<String, TaskNotification> producer;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaProtobufSerializer.class);
        producerProps.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, SCHEMA_REGISTRY_URL);
        producer = new KafkaProducer<>(producerProps);

        // design doc §05 — /readyz only turns green once the listener container has partitions
        // assigned, which is also the earliest point "auto-offset-reset: latest" has resolved a
        // starting position; publishing before that would be missed.
        awaitConsumerReady();
    }

    @AfterEach
    void tearDown() {
        producer.close();
    }

    @Test
    void anActivityNotificationPublishedToKafkaIsPushedToTheConnectedClient() throws Exception {
        CapturingHandler client = connect(null);

        publish(activityNotification("task-1"));

        Map<String, Object> envelope = decode(client.nextMessage());
        assertThat(dataOf(envelope).get("task_id")).isEqualTo("task-1");

        client.session.close();
    }

    @Test
    void reconnectingWithLastEventIdReplaysWhatWasMissedWhileDisconnected() throws Exception {
        CapturingHandler firstConnection = connect(null);
        publish(activityNotification("task-1"));
        Map<String, Object> firstEnvelope = decode(firstConnection.nextMessage());
        long firstOffset = ((Number) firstEnvelope.get("id")).longValue();
        firstConnection.session.close();

        publish(activityNotification("task-2")); // arrives while the client is disconnected

        CapturingHandler secondConnection = connect(firstOffset);

        Map<String, Object> replayed = decode(secondConnection.nextMessage());
        assertThat(dataOf(replayed).get("task_id")).isEqualTo("task-2");

        // design doc §04 register(): any resumed connection also gets a refresh, since a gap of
        // any length may have missed rollup-only changes this pod never retained.
        Map<String, Object> refresh = decode(secondConnection.nextMessage());
        assertThat(refresh.get("event")).isEqualTo("refresh");

        secondConnection.session.close();
    }

    private void awaitConsumerReady() {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            ResponseEntity<String> response = restTemplate.getForEntity("/readyz", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        });
    }

    private CapturingHandler connect(Long lastEventId) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("X-Org-Id", ORG_ID);
        headers.add("X-User-Id", USER_ID);
        if (lastEventId != null) {
            headers.add("Last-Event-ID", String.valueOf(lastEventId));
        }
        CapturingHandler handler = new CapturingHandler();
        handler.session = webSocketClient
                .execute(handler, headers, URI.create("ws://localhost:" + port + "/api/v1/events/stream"))
                .get(10, TimeUnit.SECONDS);
        return handler;
    }

    private void publish(TaskNotification notification) throws Exception {
        producer.send(new ProducerRecord<>(TOPIC, notification.getOrgId() + ":" + notification.getUserId(), notification))
                .get(10, TimeUnit.SECONDS);
    }

    private TaskNotification activityNotification(String taskId) {
        return TaskNotification.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOrgId(ORG_ID)
                .setUserId(USER_ID)
                .setTaskId(taskId)
                .setPushPolicy(PushPolicy.ACTIVITY)
                .build();
    }

    private Map<String, Object> decode(String payload) throws Exception {
        return objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() { });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("data");
    }

    private static final class CapturingHandler extends TextWebSocketHandler {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        volatile WebSocketSession session;

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        String nextMessage() throws InterruptedException {
            String message = messages.poll(15, TimeUnit.SECONDS);
            assertThat(message).as("expected a message within the timeout").isNotNull();
            return message;
        }
    }
}
