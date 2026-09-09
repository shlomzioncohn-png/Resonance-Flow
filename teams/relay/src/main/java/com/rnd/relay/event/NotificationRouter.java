package com.rnd.relay.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// design doc §03 "What gets pushed".
@Component
public class NotificationRouter {

    private static final Logger log = LoggerFactory.getLogger(NotificationRouter.class);

    static final Duration DEBOUNCE_WINDOW = Duration.ofSeconds(3);
    static final Duration MAX_DIRTY_AGE = Duration.ofSeconds(10);
    static final Duration DEDUP_TTL = Duration.ofMinutes(5);

    private final NotificationSink sink;
    private final Map<String, Instant> seenEventIds = new ConcurrentHashMap<>();
    private final Map<String, DirtyState> dirtyUsers = new ConcurrentHashMap<>();

    // design doc §09 metrics list
    private final Counter duplicateEventsCounter;
    private final Counter rollupOnlyMessagesCounter;
    private final Counter refreshSignalsEmittedCounter;

    public NotificationRouter(NotificationSink sink, MeterRegistry meterRegistry) {
        this.sink = sink;
        this.duplicateEventsCounter = meterRegistry.counter("pipe.notifications.duplicate");
        this.rollupOnlyMessagesCounter = meterRegistry.counter("pipe.notifications.rollup_only");
        this.refreshSignalsEmittedCounter = meterRegistry.counter("pipe.refresh.signals.emitted");
    }

    public void handle(TaskNotification event, long offset, Instant now) {
        if (seenEventIds.putIfAbsent(event.getEventId(), now) != null) {
            log.debug("Dropping duplicate eventId={}", event.getEventId());
            duplicateEventsCounter.increment();
            return;
        }

        switch (event.getPushPolicy()) {
            case ACTIVITY -> sink.activity(event, offset);
            case ROLLUP_ONLY -> {
                rollupOnlyMessagesCounter.increment();
                markDirty(event.getOrgId(), event.getUserId(), now);
            }
            default -> log.warn("Ignoring notification with unset/unrecognized push policy: eventId={} pushPolicy={}",
                    event.getEventId(), event.getPushPolicy());
        }
    }

    @Scheduled(fixedRate = 1000)
    void scheduledTick() {
        tick(Instant.now());
    }

    // Split from scheduledTick() so the debounce logic below can be driven by a fixed Instant in tests.
    public void tick(Instant now) {
        seenEventIds.values().removeIf(seenAt -> Duration.between(seenAt, now).compareTo(DEDUP_TTL) > 0);

        dirtyUsers.forEach((userKey, state) -> {
            boolean quiet = Duration.between(state.lastDirtyAt(), now).compareTo(DEBOUNCE_WINDOW) >= 0;
            boolean overdue = Duration.between(state.firstDirtyAt(), now).compareTo(MAX_DIRTY_AGE) >= 0;
            if ((quiet || overdue) && dirtyUsers.remove(userKey, state)) {
                String[] orgAndUser = splitUserKey(userKey);
                refreshSignalsEmittedCounter.increment();
                sink.refresh(orgAndUser[0], orgAndUser[1]);
            }
        });
    }

    private void markDirty(String orgId, String userId, Instant now) {
        dirtyUsers.compute(userKey(orgId, userId), (key, existing) ->
                existing == null ? new DirtyState(now, now) : new DirtyState(existing.firstDirtyAt(), now));
    }

    private static String userKey(String orgId, String userId) {
        return orgId + ':' + userId;
    }

    private static String[] splitUserKey(String userKey) {
        int separator = userKey.indexOf(':');
        return new String[] { userKey.substring(0, separator), userKey.substring(separator + 1) };
    }

    private record DirtyState(Instant firstDirtyAt, Instant lastDirtyAt) {
    }
}
