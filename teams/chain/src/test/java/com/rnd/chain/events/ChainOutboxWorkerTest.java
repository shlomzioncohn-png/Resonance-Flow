package com.rnd.chain.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChainOutboxWorkerTest {

    private ChainOutboxEventRepository repository;
    private KafkaEventPublisher publisher;
    private ChainOutboxWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(ChainOutboxEventRepository.class);
        publisher = mock(KafkaEventPublisher.class);
        ChainOutboxProperties properties = new ChainOutboxProperties(
                new ChainOutboxProperties.Worker(20, 5000, 3, 10, 120),
                new ChainOutboxProperties.Cleanup(3_600_000, 7));
        worker = new ChainOutboxWorker(repository, publisher, properties);
    }

    private ChainOutboxEvent pendingEvent() {
        ChainOutboxEvent event = ChainOutboxEvent.pending(
                "task.scheduling.updated",
                UUID.randomUUID(),
                "key-1",
                Map.of("taskId", "t-1"),
                UUID.randomUUID(),
                Instant.now());
        // eventId is normally DB-generated (@GeneratedValue) - set explicitly
        // here since this is a plain unit test with no persistence context.
        event.setEventId(UUID.randomUUID());
        return event;
    }

    @Test
    void processOne_marksPublished_whenPublishSucceeds() {
        ChainOutboxEvent event = pendingEvent();

        worker.processOne(event);

        assertEquals(ChainOutboxStatus.PUBLISHED, event.getStatus());
        assertNotNull(event.getPublishedAt());
        verify(publisher).publishAndWait(any());
    }

    @Test
    void processOne_schedulesRetry_whenPublishFailsAndRetriesRemain() {
        ChainOutboxEvent event = pendingEvent();
        doThrow(new EventPublishException("boom")).when(publisher).publishAndWait(any());

        worker.processOne(event);

        assertEquals(ChainOutboxStatus.PENDING, event.getStatus());
        assertEquals(1, event.getRetryCount());
        assertNotNull(event.getLastError());
        assertTrue(event.getNextRetryAt().isAfter(Instant.now()));
    }

    @Test
    void processOne_marksFailed_whenMaxRetriesExhausted() {
        ChainOutboxEvent event = pendingEvent();
        event.setRetryCount((short) 2); // one more failure reaches maxRetries=3
        doThrow(new EventPublishException("boom")).when(publisher).publishAndWait(any());

        worker.processOne(event);

        assertEquals(ChainOutboxStatus.FAILED, event.getStatus());
        assertEquals(3, event.getRetryCount());
    }

    @Test
    void computeBackoff_growsExponentially_cappedAtBackoffMax() {
        assertEquals(Duration.ofSeconds(10), worker.computeBackoff(1));
        assertEquals(Duration.ofSeconds(20), worker.computeBackoff(2));
        assertEquals(Duration.ofSeconds(40), worker.computeBackoff(3));
        assertEquals(Duration.ofSeconds(120), worker.computeBackoff(10), "capped at backoff-max-seconds");
    }

    @Test
    void pollAndPublish_processesEveryClaimedEvent() {
        ChainOutboxEvent first = pendingEvent();
        ChainOutboxEvent second = pendingEvent();
        when(repository.claimPendingBatch(any(), eq(20))).thenReturn(List.of(first, second));

        worker.pollAndPublish();

        verify(publisher, times(2)).publishAndWait(any());
        assertEquals(ChainOutboxStatus.PUBLISHED, first.getStatus());
        assertEquals(ChainOutboxStatus.PUBLISHED, second.getStatus());
    }

    @Test
    void pollAndPublish_logsAndReturns_whenClaimQueryFails() {
        when(repository.claimPendingBatch(any(), anyInt())).thenThrow(new RuntimeException("DB connection issue"));

        assertDoesNotThrow(() -> worker.pollAndPublish());
        verifyNoInteractions(publisher);
    }
}
