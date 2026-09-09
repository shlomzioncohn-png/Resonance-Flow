package com.rnd.relay.event;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

// Recent-activity history per user
@Component
class ActivityReplayBuffer {

    private final int capacityPerUser;

    private final Map<String, NavigableMap<Long, Entry>> byUser = new ConcurrentHashMap<>();

    ActivityReplayBuffer(
            @Value("${relay.pipe.replay-buffer.capacity-per-user:200}") int capacityPerUser) {
        this.capacityPerUser = capacityPerUser;
    }

    void record(
            String orgId,
            String userId,
            TaskNotification event,
            long offset,
            Instant recordedAt) {

        NavigableMap<Long, Entry> buffer =
                byUser.computeIfAbsent(userKey(orgId, userId), key -> new TreeMap<>());

        synchronized (buffer) {
            buffer.put(offset, new Entry(offset, event, recordedAt));

            while (buffer.size() > capacityPerUser) {
                buffer.pollFirstEntry();
            }
        }
    }

    Optional<List<Entry>> replaySince(
            String orgId,
            String userId,
            long lastEventId) {

        NavigableMap<Long, Entry> buffer = byUser.get(userKey(orgId, userId));

        if (buffer == null) {
            return Optional.empty();
        }

        synchronized (buffer) {
            if (buffer.isEmpty() || buffer.firstKey() > lastEventId + 1) {
                return Optional.empty();
            }

            return Optional.of(
                    new ArrayList<>(buffer.tailMap(lastEventId, false).values())
            );
        }
    }

    List<Entry> since(
            String orgId,
            String userId,
            Instant since) {

        NavigableMap<Long, Entry> buffer = byUser.get(userKey(orgId, userId));

        if (buffer == null) {
            return List.of();
        }

        synchronized (buffer) {
            List<Entry> result = new ArrayList<>();

            for (Entry entry : buffer.values()) {
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