package com.rnd.chain.events;

import com.rnd.chain.dto.ProcessedEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChainEventProcessingTemplateTest {

    private final ProcessedEventStore store = mock(ProcessedEventStore.class);
    private final ChainEventProcessingTemplate template = new ChainEventProcessingTemplate(store);

    @Test
    void processClaimed_runsTheActionOnce_forANewEventId() {
        when(store.tryClaim(any(ProcessedEvent.class))).thenReturn(true);
        AtomicInteger runs = new AtomicInteger();

        template.processClaimed(UUID.randomUUID(), "task.dependencies.proposed", runs::incrementAndGet);

        assertEquals(1, runs.get());
    }

    @Test
    void processClaimed_skipsTheAction_forAnAlreadyClaimedEventId() {
        when(store.tryClaim(any(ProcessedEvent.class))).thenReturn(false);
        AtomicInteger runs = new AtomicInteger();

        template.processClaimed(UUID.randomUUID(), "task.dependencies.proposed", runs::incrementAndGet);

        assertEquals(0, runs.get(), "a duplicate delivery must not run the action");
    }

    @Test
    void processClaimed_releasesTheClaim_whenTheActionThrows() {
        UUID eventId = UUID.randomUUID();
        when(store.tryClaim(any(ProcessedEvent.class))).thenReturn(true);

        assertThrows(RuntimeException.class, () -> template.processClaimed(eventId, "task.dependencies.proposed",
                () -> { throw new RuntimeException("boom"); }));

        verify(store).release(eventId);
    }

    @Test
    void processClaimed_propagatesTheOriginalException() {
        when(store.tryClaim(any(ProcessedEvent.class))).thenReturn(true);
        IllegalStateException original = new IllegalStateException("cycle");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> template.processClaimed(UUID.randomUUID(), "task.dependencies.proposed", () -> {
                    throw original;
                }));
        assertSame(original, thrown);
    }

    @Test
    void processUnclaimed_runsTheActionEveryTime_noClaimInvolved() {
        List<String> runs = new ArrayList<>();

        template.processUnclaimed("task-1", "task.updated", () -> runs.add("first"));
        template.processUnclaimed("task-1", "task.updated", () -> runs.add("second"));

        assertEquals(List.of("first", "second"), runs,
                "processUnclaimed has no dedup table - the same key can run any number of times, "
                        + "idempotency is the action's own responsibility");
        verifyNoInteractions(store);
    }

    @Test
    void processUnclaimed_propagatesTheOriginalException() {
        RuntimeException original = new RuntimeException("db down");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> template.processUnclaimed("task-1", "task.updated", () -> {
                    throw original;
                }));
        assertSame(original, thrown);
    }
}
