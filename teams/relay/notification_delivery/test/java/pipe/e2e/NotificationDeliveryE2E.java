package pipe.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import com.rnd.relay.event.PushPolicy;
import com.rnd.relay.event.TaskNotification;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.yaml.snakeyaml.Yaml;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

// Black-box E2E against a deployed notification-delivery
public final class NotificationDeliveryE2E {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(4);
    private static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(15);

    private final String serviceUrl;
    private final String inputTopic;
    private final KafkaProducer<String, TaskNotification> producer;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    // Unique per run, so a rerun against the same cluster never sees the previous run's events.
    private final String runId = UUID.randomUUID().toString().substring(0, 8);
    private final List<String> failures = new ArrayList<>();
    private final List<Socket> sockets = new ArrayList<>();

    private NotificationDeliveryE2E(String serviceUrl, String inputTopic, Map<String, Object> producerProps) {
        this.serviceUrl = serviceUrl;
        this.inputTopic = inputTopic;
        this.producer = new KafkaProducer<>(producerProps);
    }

    public static void main(String[] args) throws Exception {
        String configFile = env("E2E_CONFIG_FILE", "/config/config.yaml");
        String serviceUrl = env("E2E_SERVICE_URL", "http://resonance-flow-notification-delivery");
        String inputTopic = env("E2E_INPUT_TOPIC", "resonance.task-events-notifications");

        NotificationDeliveryE2E e2e = new NotificationDeliveryE2E(serviceUrl, inputTopic, producerProps(configFile));
        int exitCode;
        try {
            exitCode = e2e.run();
        } finally {
            e2e.close();
        }
        System.exit(exitCode);
    }

    private int run() throws Exception {
        log("run " + runId + " against " + serviceUrl + ", producing to " + inputTopic);
        Instant start = Instant.now();

        warmUp();
        scenario("ACTIVITY is pushed to the recipient", this::activityIsPushed);
        scenario("notifications only reach their own org:user", this::recipientsAreIsolated);
        scenario("a repeated event_id is pushed once", this::duplicatesAreDropped);
        scenario("ROLLUP_ONLY becomes a refresh signal, not an item", this::rollupOnlyBecomesRefresh);
        scenario("/sync replays ACTIVITY since a timestamp", () -> syncReplays(start));
        scenario("missing or blank identity headers are rejected", this::unauthenticatedIsRejected);

        log("");
        if (failures.isEmpty()) {
            log("ALL PASSED");
            return 0;
        }
        log(failures.size() + " FAILED:");
        failures.forEach(f -> log("  - " + f));
        return 1;
    }

    // /readyz only covers the task-notifications listener; TaskEventProcessor's group (auto-offset-reset
    // latest) may still be joining, and anything produced before it has partitions is skipped for good.
    // So keep producing a probe until one makes it through the whole pipe.
    private void warmUp() throws Exception {
        Socket socket = connect(org("warmup"), "warmup");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
        int attempt = 0;
        while (Instant.now().isBefore(deadline)) {
            String taskId = task("warmup-" + attempt++);
            produce(notification(org("warmup"), "warmup", taskId, PushPolicy.ACTIVITY));
            if (socket.await(activityFor(taskId), Duration.ofSeconds(5)) != null) {
                log("pipe warm after " + attempt + " probe(s)");
                return;
            }
        }
        throw new IllegalStateException("FAIL: no probe made it through the pipe within 120s");
    }

    private void activityIsPushed() throws Exception {
        Socket alice = connect(org("a"), "alice");
        String taskId = task("activity");
        TaskNotification sent = notification(org("a"), "alice", taskId, PushPolicy.ACTIVITY);
        produce(sent);

        JsonNode received = alice.await(activityFor(taskId), DELIVERY_TIMEOUT);
        check(received != null, "alice got no ACTIVITY for " + taskId + " within " + DELIVERY_TIMEOUT.toSeconds() + "s");
        if (received == null) {
            return;
        }
        JsonNode data = received.path("data");
        check(data.path("urgency_score").asDouble() == sent.getUrgencyScore(), "urgency_score mismatch: " + received);
        check(data.path("effort_estimate_minutes").asInt() == sent.getEffortEstimateMinutes(), "effort_estimate_minutes mismatch: " + received);
        check(sent.getSourceRef().equals(data.path("source_ref").asText()), "source_ref mismatch: " + received);
        // id is the Kafka offset - what a client sends back as Last-Event-ID on reconnect.
        check(received.path("id").isNumber() && received.path("id").asLong() == data.path("offset").asLong(),
                "envelope id must equal data.offset: " + received);
    }

    private void recipientsAreIsolated() throws Exception {
        Socket alice = connect(org("a"), "alice");
        Socket bob = connect(org("a"), "bob");
        // Same user_id in another org - the key is org:user, so this must not collide with org-a's alice.
        Socket otherOrgAlice = connect(org("b"), "alice");

        String forBob = task("for-bob");
        String forOtherOrg = task("for-org-b-alice");
        produce(notification(org("a"), "bob", forBob, PushPolicy.ACTIVITY));
        produce(notification(org("b"), "alice", forOtherOrg, PushPolicy.ACTIVITY));

        check(bob.await(activityFor(forBob), DELIVERY_TIMEOUT) != null, "bob never got his own notification " + forBob);
        check(otherOrgAlice.await(activityFor(forOtherOrg), DELIVERY_TIMEOUT) != null,
                "org-b alice never got her own notification " + forOtherOrg);
        check(alice.await(activityFor(forBob).or(activityFor(forOtherOrg)), QUIET_PERIOD) == null,
                "org-a alice received a notification meant for someone else");
    }

    private void duplicatesAreDropped() throws Exception {
        Socket alice = connect(org("a"), "alice");
        String taskId = task("dup");
        TaskNotification event = notification(org("a"), "alice", taskId, PushPolicy.ACTIVITY);
        produce(event);
        produce(event);

        check(alice.await(activityFor(taskId), DELIVERY_TIMEOUT) != null, "first copy of " + taskId + " never arrived");
        check(alice.await(activityFor(taskId), QUIET_PERIOD) == null, "duplicate event_id " + event.getEventId() + " was pushed twice");
    }

    private void rollupOnlyBecomesRefresh() throws Exception {
        Socket alice = connect(org("a"), "alice");
        String taskId = task("rollup");
        produce(notification(org("a"), "alice", taskId, PushPolicy.ROLLUP_ONLY));

        JsonNode refresh = alice.await(m -> "refresh".equals(m.path("event").asText()) || isActivityFor(m, taskId), REFRESH_TIMEOUT);
        check(refresh != null, "no refresh signal within " + REFRESH_TIMEOUT.toSeconds() + "s of a ROLLUP_ONLY");
        check(refresh == null || !isActivityFor(refresh, taskId), "ROLLUP_ONLY " + taskId + " was pushed as an activity item");
    }

    private void syncReplays(Instant since) throws Exception {
        // The pod behind the Service may not be the one that pushed it, so this goes through either
        // the local replay buffer or the Kafka fallback - both must return it.
        HttpResponse<String> response = sync(org("a"), "alice", since);
        check(response.statusCode() == 200, "/sync returned HTTP " + response.statusCode() + ": " + response.body());
        if (response.statusCode() != 200) {
            return;
        }
        List<String> taskIds = new ArrayList<>();
        JSON.readTree(response.body()).forEach(item -> taskIds.add(item.path("task_id").asText()));
        check(taskIds.contains(task("activity")), "/sync is missing " + task("activity") + ", got " + taskIds);
        check(!taskIds.contains(task("for-bob")), "/sync for alice returned bob's " + task("for-bob"));
        check(!taskIds.contains(task("rollup")), "/sync returned ROLLUP_ONLY " + task("rollup"));
    }

    private void unauthenticatedIsRejected() throws Exception {
        expectHandshakeRejected(Map.of(), "no identity headers");
        expectHandshakeRejected(Map.of("X-Org-Id", org("a"), "X-User-Id", " "), "blank X-User-Id");
        expectHandshakeRejected(Map.of("X-Org-Id", "", "X-User-Id", "alice"), "blank X-Org-Id");

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(serviceUrl + "/api/v1/events/sync?since=0"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == 400, "/sync without identity headers returned HTTP " + response.statusCode() + ", expected 400");
    }

    private void expectHandshakeRejected(Map<String, String> headers, String what) throws InterruptedException {
        WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5));
        headers.forEach(builder::header);
        try {
            WebSocket ws = builder.buildAsync(streamUri(), new Socket()).join();
            ws.abort();
            check(false, "handshake with " + what + " was accepted");
        } catch (CompletionException e) {
            int status = e.getCause() instanceof WebSocketHandshakeException hs ? hs.getResponse().statusCode() : -1;
            check(status == 400, "handshake with " + what + " failed with " + e.getCause() + ", expected HTTP 400");
        }
    }

    // The handshake completes before the server registers the session, so a notification produced
    // right after join() can miss it. A Last-Event-ID makes the server send a refresh once it has
    // registered - wait for that. Long.MAX_VALUE - 1 is past every offset, so nothing is replayed.
    private Socket connect(String orgId, String userId) throws InterruptedException {
        Socket socket = new Socket();
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .header("X-Org-Id", orgId)
                .header("X-User-Id", userId)
                .header("Last-Event-ID", Long.toString(Long.MAX_VALUE - 1))
                .buildAsync(streamUri(), socket)
                .join();
        sockets.add(socket);
        if (socket.await(m -> "refresh".equals(m.path("event").asText()), Duration.ofSeconds(10)) == null) {
            throw new IllegalStateException(orgId + ":" + userId + " connected but was never registered (no refresh within 10s)");
        }
        return socket;
    }

    private HttpResponse<String> sync(String orgId, String userId, Instant since) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(serviceUrl + "/api/v1/events/sync?since=" + since.toEpochMilli()))
                .timeout(Duration.ofSeconds(30))
                .header("X-Org-Id", orgId)
                .header("X-User-Id", userId)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI streamUri() {
        return URI.create(serviceUrl.replaceFirst("^http", "ws") + "/api/v1/events/stream");
    }

    private void produce(TaskNotification event) throws Exception {
        // Same key the pipe uses, so per-user ordering matches production.
        String key = event.getOrgId() + ':' + event.getUserId();
        producer.send(new ProducerRecord<>(inputTopic, key, event)).get(10, TimeUnit.SECONDS);
    }

    private TaskNotification notification(String orgId, String userId, String taskId, PushPolicy policy) {
        Instant now = Instant.now();
        return TaskNotification.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOrgId(orgId)
                .setUserId(userId)
                .setTaskId(taskId)
                .setPushPolicy(policy)
                .setUrgencyScore(7.5)
                .setEffortEstimateMinutes(45)
                .setOccurredAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
                .setSourceRef("e2e:" + taskId)
                .build();
    }

    private String org(String name) {
        return "e2e-" + runId + "-org-" + name;
    }

    private String task(String name) {
        return "e2e-" + runId + "-" + name;
    }

    private static Predicate<JsonNode> activityFor(String taskId) {
        return m -> isActivityFor(m, taskId);
    }

    private static boolean isActivityFor(JsonNode message, String taskId) {
        return taskId.equals(message.path("data").path("task_id").asText());
    }

    private void scenario(String name, ThrowingRunnable body) {
        int failuresBefore = failures.size();
        try {
            body.run();
        } catch (Exception e) {
            failures.add(name + ": " + e);
        }
        if (failures.size() == failuresBefore) {
            log("PASS: " + name);
        } else {
            for (String failure : failures.subList(failuresBefore, failures.size())) {
                log("FAIL: " + name + " - " + failure);
            }
        }
    }

    private void check(boolean condition, String failure) {
        if (!condition) {
            failures.add(failure);
        }
    }

    private void close() {
        sockets.forEach(Socket::close);
        producer.close(Duration.ofSeconds(5));
    }

    // Kafka client properties from the service's own mounted config.yaml (spring.kafka.*).
    @SuppressWarnings("unchecked")
    private static Map<String, Object> producerProps(String configFile) throws IOException {
        Map<String, Object> kafka;
        try (Reader reader = new FileReader(configFile)) {
            Map<String, Object> root = new Yaml().load(reader);
            kafka = (Map<String, Object>) ((Map<String, Object>) root.get("spring")).get("kafka");
        }
        Map<String, Object> props = new HashMap<>((Map<String, Object>) kafka.get("properties"));
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.get("bootstrap-servers"));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaProtobufSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        // auto.register.schemas stays on, like the service's own producer. With it off, the serializer
        // looks the schema up by its canonical text, which never matches the raw .proto that
        // schema-sync-hook registers (Apicurio compares literally) - every produce fails with 40403.
        return props;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void log(String line) {
        System.out.println(line);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // Collects every text message; await() consumes the first one matching, so assertions on later
    // messages aren't confused by earlier ones (heartbeats are pings, which never land here).
    private static final class Socket implements WebSocket.Listener {

        private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();
        private WebSocket webSocket;

        @Override
        public void onOpen(WebSocket webSocket) {
            this.webSocket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    received.add(JSON.readTree(partial.toString()));
                } catch (IOException e) {
                    System.out.println("WARN: non-JSON message: " + partial);
                }
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        JsonNode await(Predicate<JsonNode> matching, Duration timeout) throws InterruptedException {
            Instant deadline = Instant.now().plus(timeout);
            List<JsonNode> skipped = new ArrayList<>();
            try {
                while (true) {
                    long remaining = Duration.between(Instant.now(), deadline).toMillis();
                    JsonNode message = remaining > 0 ? received.poll(remaining, TimeUnit.MILLISECONDS) : null;
                    if (message == null) {
                        return null;
                    }
                    if (matching.test(message)) {
                        return message;
                    }
                    skipped.add(message);
                }
            } finally {
                received.addAll(skipped);
            }
        }

        void close() {
            if (webSocket != null) {
                webSocket.abort();
            }
        }
    }
}
