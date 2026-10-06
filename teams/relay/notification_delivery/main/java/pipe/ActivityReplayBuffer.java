package pipe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Recent-activity history per user.
@Component
class ActivityReplayBuffer {

    private final int capacityPerUser;

    private final Map<String, RingBuffer> byUser = new ConcurrentHashMap<>();

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

        RingBuffer buffer =
                byUser.computeIfAbsent(userKey(orgId, userId), key -> new RingBuffer(capacityPerUser));

        buffer.add(new Entry(offset, event, recordedAt));
    }

    Optional<List<Entry>> replaySince(
            String orgId,
            String userId,
            long lastEventId) {

        RingBuffer buffer = byUser.get(userKey(orgId, userId));

        if (buffer == null) {
            return Optional.empty();
        }

        return buffer.replaySince(lastEventId);
    }

    Optional<List<Entry>> since(
            String orgId,
            String userId,
            Instant since) {

        RingBuffer buffer = byUser.get(userKey(orgId, userId));

        if (buffer == null) {
            return Optional.empty();
        }

        return buffer.since(since);
    }

    private static String userKey(String orgId, String userId) {
        return orgId + ':' + userId;
    }

    record Entry(long offset, TaskNotification event, Instant recordedAt) {
    }

    private static final class RingBuffer {

        private final Entry[] entries;
        private int head = 0;
        private int size = 0;

        RingBuffer(int capacity) {
            this.entries = new Entry[capacity];
        }

        synchronized void add(Entry entry) {
            entries[(head + size) % entries.length] = entry;
            if (size < entries.length) {
                size++;
            } else {
                head = (head + 1) % entries.length;
            }
        }

        synchronized Optional<List<Entry>> replaySince(long lastEventId) {
            if (size == 0 || entries[head].offset() > lastEventId + 1) {
                return Optional.empty();
            }

            List<Entry> result = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                Entry entry = entries[(head + i) % entries.length];
                if (entry.offset() > lastEventId) {
                    result.add(entry);
                }
            }
            return Optional.of(result);
        }

        synchronized Optional<List<Entry>> since(Instant since) {
            if (size == 0 || entries[head].recordedAt().isAfter(since)) {
                return Optional.empty();
            }

            List<Entry> result = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                Entry entry = entries[(head + i) % entries.length];
                if (entry.recordedAt().isAfter(since)) {
                    result.add(entry);
                }
            }
            return Optional.of(result);
        }
    }
}