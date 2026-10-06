package com.rnd.chain.events;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ChainEventProcessingTemplateTest {

    @Test
    void processClaimed_runsTheActionOnce_forANewEventId() {
        ChainEventProcessingTemplate template = new ChainEventProcessingTemplate(new InMemoryProcessedEventStore());
        AtomicInteger runs = new AtomicInteger();

        template.processClaimed(UUID.randomUUID(), "task.dependencies.proposed", runs::incrementAndGet);

        assertEquals(1, runs.get());
    }

    @Test
    void processClaimed_skipsTheAction_forAnAlreadyClaimedEventId() {
        InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();
        ChainEventProcessingTemplate template = new ChainEventProcessingTemplate(store);
        UUID eventId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        template.processClaimed(eventId, "task.dependencies.proposed", runs::incrementAndGet);
        template.processClaimed(eventId, "task.dependencies.proposed", runs::incrementAndGet);

        assertEquals(1, runs.get(), "the second (duplicate) delivery must not run the action again");
    }

    @Test
    void processClaimed_releasesTheClaim_whenTheActionThrows() {
        InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();
        ChainEventProcessingTemplate template = new ChainEventProcessingTemplate(store);
        UUID eventId = UUID.randomUUID();

        assertThrows(RuntimeException.class, () -> template.processClaimed(eventId, "task.dependencies.proposed",
                () -> { throw new RuntimeException("boom"); }));

        AtomicInteger runsAfterRelease = new AtomicInteger();
        template.processClaimed(eventId, "task.dependencies.proposed", runsAfterRelease::incrementAndGet);
        assertEquals(1, runsAfterRelease.get(),
                "a released claim must be retriable - a retry/redelivery of the same eventId should run the action");
    }

    @Test
    void processClaimed_propagatesTheOriginalException() {
        ChainEventProcessingTemplate template = new ChainEventProcessingTemplate(new InMemoryProcessedEventStore());
        IllegalStateException original = new IllegalStateException("cycle");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> template.processClaimed(UUID.randomUUID(), "task.dependencies.proposed", () -> {
                    throw original;
                }));
        assertSame(original, thrown);
    }

    @Test
    void processUnclaimed_runsTheActionEveryTime_noClaimInvolved() {
        ChainEventProcessingTemplate template = new ChainEventProcessingTemplate(new InMemoryProcessedEventStore());
        List<String> runs = new ArrayList<>();

        template.processUnclaimed("task-1", "task.updated", () -> runs.add("first"));
        template.processUnclaimed("task-1", "task.updated", () -> runs.add("second"));

        assertEquals(List.of("first", "second"), runs,
                "processUnclaimed has no dedup table - the same key can run any number of times, "
                        + "idempotency is the action's own responsibility");
    }

    @Test
    void processUnclaimed_propagatesTheOriginalException() {
        ChainEventProcessingTemplate template = new ChainEventProcessingTemplate(new InMemoryProcessedEventStore());
        RuntimeException original = new RuntimeException("db down");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> template.processUnclaimed("task-1", "task.updated", () -> {
                    throw original;
                }));
        assertSame(original, thrown);
    }
}
