package pipe;

import com.rnd.relay.event.PushPolicy;
import com.rnd.relay.event.TaskNotification;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class NotificationRouterTest {

    private static final String ORG_ID = "org-1";
    private static final String USER_ID = "user-1";
    private static final Duration DEBOUNCE_WINDOW = Duration.ofSeconds(3);
    private static final Duration MAX_DIRTY_AGE = Duration.ofSeconds(10);
    private static final Duration DEDUP_TTL = Duration.ofMinutes(5);

    @Mock
    NotificationSink sink;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final Instant t0 = Instant.parse("2026-08-30T09:00:00Z");

    @Test
    void activityPolicyIsDeliveredImmediately() {
        NotificationRouter router = new NotificationRouter(sink, meterRegistry, DEBOUNCE_WINDOW, MAX_DIRTY_AGE, DEDUP_TTL);
        TaskNotification activityTask = notification(PushPolicy.ACTIVITY, t0);

        router.handle(activityTask, 42L, t0);

        verify(sink).activity(activityTask, 42L);
        verifyNoMoreInteractions(sink);
    }

    @Test
    void duplicateEventIdIsDroppedOnRedeliveryAndCounted() {
        NotificationRouter router = new NotificationRouter(sink, meterRegistry, DEBOUNCE_WINDOW, MAX_DIRTY_AGE, DEDUP_TTL);
        TaskNotification activityTask = notification( PushPolicy.ACTIVITY, t0);
        TaskNotification retriedCopy = activityTask.toBuilder().build(); // same event_id, simulating a producer retry

        router.handle(activityTask, 1L, t0);
        router.handle(retriedCopy, 2L, t0.plusSeconds(1));

        verify(sink).activity(activityTask, 1L);
        verifyNoMoreInteractions(sink);
        assertThat(meterRegistry.counter("pipe.notifications.duplicate").count()).isEqualTo(1.0);
    }

    @Test
    void rollupOnlyPolicyIsHeldUntilTheDebounceWindowGoesQuiet() {
        NotificationRouter router = new NotificationRouter(sink, meterRegistry, DEBOUNCE_WINDOW, MAX_DIRTY_AGE, DEDUP_TTL);
        router.handle(notification(PushPolicy.ROLLUP_ONLY, t0), 1L, t0);

        router.tick(t0.plus(DEBOUNCE_WINDOW).minusSeconds(1));
        verify(sink, never()).refresh(ORG_ID, USER_ID);

        router.tick(t0.plus(DEBOUNCE_WINDOW));
        verify(sink).refresh(ORG_ID, USER_ID);
        verifyNoMoreInteractions(sink);
        assertThat(meterRegistry.counter("pipe.notifications.rollup_only").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("pipe.refresh.signals.emitted").count()).isEqualTo(1.0);
    }

    @Test
    void burstOfRollupOnlyEventsCollapsesIntoOneRefresh() {
        NotificationRouter router = new NotificationRouter(sink, meterRegistry, DEBOUNCE_WINDOW, MAX_DIRTY_AGE, DEDUP_TTL);
        Instant lastEventAt = t0;
        for (int i = 0; i < 50; i++) {
            lastEventAt = t0.plusMillis(i * 100L);
            router.handle(notification(PushPolicy.ROLLUP_ONLY, lastEventAt), i, lastEventAt);
        }

        router.tick(lastEventAt.plus(DEBOUNCE_WINDOW));

        verify(sink, never()).activity(any(), anyLong());
        verify(sink).refresh(ORG_ID, USER_ID);
        verifyNoMoreInteractions(sink);
        assertThat(meterRegistry.counter("pipe.notifications.rollup_only").count()).isEqualTo(50.0);
        assertThat(meterRegistry.counter("pipe.refresh.signals.emitted").count()).isEqualTo(1.0);
    }

    @Test
    void continuousChurnStillFlushesWithinTheMaxDirtyAge() {
        NotificationRouter router = new NotificationRouter(sink, meterRegistry, DEBOUNCE_WINDOW, MAX_DIRTY_AGE, DEDUP_TTL);
        Instant lastEventAt = t0;
        long offset = 0;
        // an event every second, never a DEBOUNCE_WINDOW-sized gap, for longer than MAX_DIRTY_AGE
        while (lastEventAt.isBefore(t0.plus(MAX_DIRTY_AGE).plusSeconds(2))) {
            router.handle(notification(PushPolicy.ROLLUP_ONLY, lastEventAt), offset++, lastEventAt);
            router.tick(lastEventAt);
            lastEventAt = lastEventAt.plusSeconds(1);
        }

        verify(sink).refresh(ORG_ID, USER_ID);
    }

    @Test
    void unsetPushPolicyIsIgnored() {
        NotificationRouter router = new NotificationRouter(sink, meterRegistry, DEBOUNCE_WINDOW, MAX_DIRTY_AGE, DEDUP_TTL);
        router.handle(notification(PushPolicy.PUSH_POLICY_UNSPECIFIED, t0), 1L, t0);
        router.tick(t0.plus(MAX_DIRTY_AGE));

        verifyNoMoreInteractions(sink);
    }

    private static TaskNotification notification(PushPolicy pushPolicy, Instant now) {
        return TaskNotification.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOrgId(ORG_ID)
                .setUserId(USER_ID)
                .setTaskId(UUID.randomUUID().toString())
                .setPushPolicy(pushPolicy)
                .build();
    }
}
