package pipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.relay.event.TaskNotification;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Component
public class WebSocketNotificationRegistry implements NotificationSink {

    private static final Logger log = LoggerFactory.getLogger(WebSocketNotificationRegistry.class);

    // see design doc §06 pitfall 5.
    private final int outboxCapacity;

    private final Map<String, Set<Connection>> connectionsByUser = new ConcurrentHashMap<>();
    private final Map<String, Connection> connectionsBySessionId = new ConcurrentHashMap<>();
    private final ActivityReplayBuffer replayBuffer;
    private final KafkaNotificationReplayService kafkaReplayService;
    private final ObjectMapper objectMapper;

    // design doc §09 metrics list.
    private final Counter reconnectCounter;
    private final Counter droppedConnectionsCounter;
    private final Counter deliveryFailuresCounter;
    private final Counter localReplayHitCounter;
    private final Counter kafkaReplayHitCounter;
    private final Counter replayMissCounter;

    public WebSocketNotificationRegistry(
            ActivityReplayBuffer replayBuffer,
            KafkaNotificationReplayService kafkaReplayService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${relay.pipe.websocket.outbox-capacity:32}") int outboxCapacity) {
        this.replayBuffer = replayBuffer;
        this.kafkaReplayService = kafkaReplayService;
        this.objectMapper = objectMapper;
        this.outboxCapacity = outboxCapacity;
        this.reconnectCounter = meterRegistry.counter("pipe.connections.reconnect");
        this.droppedConnectionsCounter = meterRegistry.counter("pipe.connections.dropped");
        this.deliveryFailuresCounter = meterRegistry.counter("pipe.delivery.failures");
        this.localReplayHitCounter = meterRegistry.counter("pipe.replay.outcome", "result", "local_hit");
        this.kafkaReplayHitCounter = meterRegistry.counter("pipe.replay.outcome", "result", "kafka_hit");
        this.replayMissCounter = meterRegistry.counter("pipe.replay.outcome", "result", "miss");
        Gauge.builder("pipe.websocket.connections.active", connectionsByUser,
                        map -> map.values().stream().mapToInt(Set::size).sum())
                .register(meterRegistry);
    }

    public void register(WebSocketSession session, String orgId, String userId, Long lastEventId) {
        Connection connection = new Connection(session, orgId, userId, outboxCapacity, deliveryFailuresCounter);
        connection.start();
        connectionsBySessionId.put(session.getId(), connection);
        connectionsByUser.computeIfAbsent(userKey(orgId, userId), key -> new CopyOnWriteArraySet<>())
                .add(connection);

        if (lastEventId != null) {
            reconnectCounter.increment();
            replayBuffer.replaySince(orgId, userId, lastEventId).ifPresentOrElse(
                    entries -> {
                        localReplayHitCounter.increment();
                        flushReplay(connection, entries);
                    },
                    () -> replayFromKafka(connection, orgId, userId, lastEventId));

            // A gap of any length may have missed rollup-only changes this pod never retained.
            refresh(orgId, userId);
        }
    }

    public void unregister(WebSocketSession session) {
        Connection connection = connectionsBySessionId.remove(session.getId());
        if (connection == null) {
            return;
        }
        connection.stop();
        Set<Connection> connections = connectionsByUser.get(userKey(connection.orgId, connection.userId));
        if (connections != null) {
            connections.remove(connection);
        }
    }

    // fall back to Kafka (design doc §06 pitfall 1).
    private void replayFromKafka(Connection connection, String orgId, String userId, long lastEventId) {
        List<ActivityReplayBuffer.Entry> kafkaReplay = kafkaReplayService.replaySince(orgId, userId, lastEventId);
        if (kafkaReplay.isEmpty()) {
            replayMissCounter.increment();
            log.info("No local or Kafka replay history for orgId={} userId={} lastEventId={} — resuming live only",
                    orgId, userId, lastEventId);
            return;
        }
        kafkaReplayHitCounter.increment();
        flushReplay(connection, kafkaReplay);
    }

    private void flushReplay(Connection connection, List<ActivityReplayBuffer.Entry> entries) {
        try {
            for (ActivityReplayBuffer.Entry entry : entries) {
                connection.putReplay(activityMessage(entry.event(), entry.offset()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int connectionCount(String orgId, String userId) {
        Set<Connection> connections = connectionsByUser.get(userKey(orgId, userId));
        return connections == null ? 0 : connections.size();
    }

    @Override
    public void activity(TaskNotification event, long offset) {
        replayBuffer.record(event.getOrgId(), event.getUserId(), event, offset, Instant.now());
        deliver(event.getOrgId(), event.getUserId(), () -> activityMessage(event, offset));
    }

    @Override
    public void refresh(String orgId, String userId) {
        deliver(orgId, userId, () -> refreshMessage());
    }

    // design doc §11 (relay.pipe.websocket.heartbeat-interval-seconds) — see pipe-limits.yaml.
    @Scheduled(fixedRateString = "#{${relay.pipe.websocket.heartbeat-interval-seconds:15} * 1000}")
    void heartbeat() {
        connectionsByUser.values().forEach(connections -> {
            for (Connection connection : connections) {
                if (!connection.offer(new PingMessage())) {
                    droppedConnectionsCounter.increment();
                    dropConnection(connections, connection);
                }
            }
        });
    }

    private void deliver(String orgId, String userId, Supplier<WebSocketMessage<?>> messageSupplier) {
        Set<Connection> connections = connectionsByUser.get(userKey(orgId, userId));
        if (connections == null || connections.isEmpty()) {
            return;
        }
        for (Connection connection : connections) {
            if (!connection.offer(messageSupplier.get())) {
                log.warn("Dropping connection orgId={} userId={} — outbox full", orgId, userId);
                droppedConnectionsCounter.increment();
                dropConnection(connections, connection);
            }
        }
    }

    private static void dropConnection(Set<Connection> connections, Connection connection) {
        connection.stop();
        connections.remove(connection);
    }

    private TextMessage activityMessage(TaskNotification event, long offset) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", offset);
        envelope.put("data", NotificationPayloads.toMap(event, offset));
        return toTextMessage(envelope);
    }

    private TextMessage refreshMessage() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event", "refresh");
        envelope.put("data", Map.of());
        return toTextMessage(envelope);
    }

    private TextMessage toTextMessage(Map<String, Object> envelope) {
        try {
            return new TextMessage(objectMapper.writeValueAsString(envelope));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize WebSocket notification envelope", e);
        }
    }

    private static String userKey(String orgId, String userId) {
        return orgId + ':' + userId;
    }

    private static final class Connection {

        private static final AtomicLong SEQUENCE = new AtomicLong();

        private final WebSocketSession session;
        private final String orgId;
        private final String userId;
        private final BlockingQueue<WebSocketMessage<?>> outbox;
        private final Thread writer;
        private final Counter deliveryFailuresCounter;

        Connection(WebSocketSession session, String orgId, String userId, int outboxCapacity, Counter deliveryFailuresCounter) {
            this.session = session;
            this.orgId = orgId;
            this.userId = userId;
            this.outbox = new ArrayBlockingQueue<>(outboxCapacity);
            this.deliveryFailuresCounter = deliveryFailuresCounter;
            this.writer = Thread.ofVirtual().name("ws-writer-" + SEQUENCE.getAndIncrement()).unstarted(this::writeLoop);
        }

        void start() {
            writer.start();
        }

        boolean offer(WebSocketMessage<?> message) {
            return outbox.offer(message);
        }

        void putReplay(WebSocketMessage<?> message) throws InterruptedException {
            outbox.put(message);
        }

        void stop() {
            writer.interrupt();
            try {
                session.close();
            } catch (IOException ignored) {
                // already closed/closing — nothing more to do
            }
        }

        private void writeLoop() {
            try {
                while (true) {
                    session.sendMessage(outbox.take());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                deliveryFailuresCounter.increment();
                stop();
            }
        }
    }
}
