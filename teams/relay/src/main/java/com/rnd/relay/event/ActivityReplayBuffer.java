package com.rnd.relay.event;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Recent-activity history per user, kept on every pod regardless of whether that pod currently
// holds a connection for them — routing isn't sticky (design doc Fig. 1), so a reconnect can land
// on any pod, and only a pod that's been recording all along can serve it from memory. This is the
// HIT path of design doc §06 pitfall 1 / Fig. 3; KafkaNotificationReplayService covers the MISS path.
// A Spring bean (not just a private field of WebSocketNotificationRegistry) because NotificationSyncController
// also reads it for the REST catch-up endpoint (design doc §07).
@Component
class ActivityReplayBuffer {

    // design doc §11 (relay.pipe.replay-buffer.capacity-per-user) — see pipe-limits.yaml.
    private final int capacityPerUser;

    private final Map<String, Deque<Entry>> byUser = new ConcurrentHashMap<>();

    ActivityReplayBuffer(@Value("${relay.pipe.replay-buffer.capacity-per-user:200}") int capacityPerUser) {
        this.capacityPerUser = capacityPerUser;
    }

    // recordedAt is caller-supplied (rather than read internally via Instant.now()) so since()
    // below stays deterministic and testable, matching how NotificationRouter takes an explicit
    // Instant instead of reading the clock itself.
    void record(String orgId, String userId, TaskNotification event, long offset, Instant recordedAt) {
        Deque<Entry> buffer = byUser.computeIfAbsent(userKey(orgId, userId), key -> new ArrayDeque<>(capacityPerUser));
        synchronized (buffer) {
            buffer.addLast(new Entry(offset, event, recordedAt));
            while (buffer.size() > capacityPerUser) {
                buffer.removeFirst();
            }
        }
    }

    // Empty means MISS: nothing retained for this user, or there's a hole between lastEventId and
    // the oldest retained entry — oldest == lastEventId + 1 is still contiguous (no hole), a HIT.
    Optional<List<Entry>> replaySince(String orgId, String userId, long lastEventId) {
        Deque<Entry> buffer = byUser.get(userKey(orgId, userId));
        if (buffer == null) {
            return Optional.empty();
        }
        synchronized (buffer) {
            if (buffer.isEmpty() || buffer.peekFirst().offset() > lastEventId + 1) {
                return Optional.empty();
            }
            List<Entry> replay = new ArrayList<>();
            for (Entry entry : buffer) {
                if (entry.offset() > lastEventId) {
                    replay.add(entry);
                }
            }
            return Optional.of(replay);
        }
    }

    // Used by the REST catch-up endpoint (design doc §07 GET /api/v1/events/sync) — a snapshot of
    // this pod's local history, not a durable guarantee: same caveat as replaySince(), just keyed
    // by wall-clock time instead of offset since a REST poller has no Last-Event-ID to resume from.
    List<Entry> since(String orgId, String userId, Instant since) {
        Deque<Entry> buffer = byUser.get(userKey(orgId, userId));
        if (buffer == null) {
            return List.of();
        }
        synchronized (buffer) {
            List<Entry> result = new ArrayList<>();
            for (Entry entry : buffer) {
                if (entry.recordedAt().isAfter(since)) {
                    result.add(entry);
                }
            }
            return result;
        }
    }

    private static String userKey(String orgId, String userId) {
        return orgId + ':' + userId;
    }

    record Entry(long offset, TaskNotification event, Instant recordedAt) {
    }
}
