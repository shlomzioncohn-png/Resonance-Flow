package com.rnd.chain.events;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.chain.dto.ProcessedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChainEventConsumerTest {

    private static final String TEST_EVENT_TYPE = "test.event.created";
    private static final String OTHER_EVENT_TYPE = "test.event.updated";

    private ObjectMapper objectMapper;
    private ProcessedEventStore processedEventStore;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        processedEventStore = mock(ProcessedEventStore.class);
        // Default: nothing has been processed yet, unless a test overrides this.
        when(processedEventStore.isProcessed(any())).thenReturn(false);
    }

    @Test
    void onMessage_routesEventToCorrectHandler_whenEventTypeMatches() throws Exception {
        EventHandler<TestPayload> handler = mockHandler(TEST_EVENT_TYPE);
        ChainEventConsumer consumer = new ChainEventConsumer(objectMapper, List.of(handler), processedEventStore);

        UUID eventId = UUID.randomUUID();
        String message = buildEnvelopeJson(eventId, TEST_EVENT_TYPE, "corr-1", "{\"value\":\"hello\"}");

        consumer.onMessage(message);

        ArgumentCaptor<EventEnvelope<TestPayload>> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(handler).handle(captor.capture());
        assertEquals(eventId, captor.getValue().getEventId());
        assertEquals(TEST_EVENT_TYPE, captor.getValue().getEventType());
        assertEquals("hello", captor.getValue().getPayload().getValue());
    }

    @Test
    void onMessage_ignoresMessage_whenEventTypeIsUnknown() throws Exception {
        // No handler registered for any event type.
        ChainEventConsumer consumer = new ChainEventConsumer(objectMapper, List.of(), processedEventStore);

        String message = buildEnvelopeJson(UUID.randomUUID(), "unknown.event.type", "corr-1", "{\"value\":\"hello\"}");

        assertDoesNotThrow(() -> consumer.onMessage(message));
        verifyNoInteractions(processedEventStore);
    }

    @Test
    void onMessage_throwsException_whenMessageIsMalformedJson() {
        EventHandler<TestPayload> handler = mockHandler(TEST_EVENT_TYPE);
        ChainEventConsumer consumer = new ChainEventConsumer(objectMapper, List.of(handler), processedEventStore);

        String malformedMessage = "not valid json at all";

        assertThrows(Exception.class, () -> consumer.onMessage(malformedMessage));
    }

    @Test
    void onMessage_throwsException_whenPayloadDoesNotMatchHandlerType() throws Exception {
        EventHandler<TestPayload> handler = mockHandler(TEST_EVENT_TYPE);
        ChainEventConsumer consumer = new ChainEventConsumer(objectMapper, List.of(handler), processedEventStore);

        // "payload" is a plain string here, not an object with a "value" field -
        // TestPayload cannot be built from it.
        String message = buildEnvelopeJson(UUID.randomUUID(), TEST_EVENT_TYPE, "corr-1", "\"not-an-object\"");

        assertThrows(JsonMappingException.class, () -> consumer.onMessage(message));
        verify(handler, never()).handle(any());
    }

    @Test
    void onMessage_propagatesException_whenHandlerProcessingFails() throws Exception {
        EventHandler<TestPayload> handler = mockHandler(TEST_EVENT_TYPE);
        doThrow(new RuntimeException("business logic failed")).when(handler).handle(any());
        ChainEventConsumer consumer = new ChainEventConsumer(objectMapper, List.of(handler), processedEventStore);

        String message = buildEnvelopeJson(UUID.randomUUID(), TEST_EVENT_TYPE, "corr-1", "{\"value\":\"hello\"}");

        assertThrows(RuntimeException.class, () -> consumer.onMessage(message));
        // The event must NOT be marked as processed if the handler failed,
        // so a retry from DefaultErrorHandler will call the handler again.
        verify(processedEventStore, never()).markProcessed(any());
    }

    @Test
    void onMessage_skipsHandler_whenEventAlreadyProcessed() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(processedEventStore.isProcessed(eventId)).thenReturn(true);

        EventHandler<TestPayload> handler = mockHandler(TEST_EVENT_TYPE);
        ChainEventConsumer consumer = new ChainEventConsumer(objectMapper, List.of(handler), processedEventStore);

        String message = buildEnvelopeJson(eventId, TEST_EVENT_TYPE, "corr-1", "{\"value\":\"hello\"}");

        consumer.onMessage(message);

        verify(handler, never()).handle(any());
        verify(processedEventStore, never()).markProcessed(any());
    }

    @Test
    void onMessage_marksEventProcessed_afterHandlerSucceeds() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventHandler<TestPayload> handler = mockHandler(TEST_EVENT_TYPE);
        ChainEventConsumer consumer = new ChainEventConsumer(objectMapper, List.of(handler), processedEventStore);

        String message = buildEnvelopeJson(eventId, TEST_EVENT_TYPE, "corr-1", "{\"value\":\"hello\"}");

        consumer.onMessage(message);

        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventStore).markProcessed(captor.capture());
        assertEquals(eventId, captor.getValue().getEventId());
        assertEquals(TEST_EVENT_TYPE, captor.getValue().getEventType());
    }

    @Test
    void onMessage_routesToCorrectHandler_whenMultipleHandlersAreRegistered() throws Exception {
        EventHandler<TestPayload> createdHandler = mockHandler(TEST_EVENT_TYPE);
        EventHandler<TestPayload> updatedHandler = mockHandler(OTHER_EVENT_TYPE);
        ChainEventConsumer consumer = new ChainEventConsumer(
                objectMapper, List.of(createdHandler, updatedHandler), processedEventStore);

        String message = buildEnvelopeJson(UUID.randomUUID(), OTHER_EVENT_TYPE, "corr-1", "{\"value\":\"hello\"}");

        consumer.onMessage(message);

        verify(updatedHandler).handle(any());
        verify(createdHandler, never()).handle(any());
    }

    @SuppressWarnings("unchecked")
    private EventHandler<TestPayload> mockHandler(String eventType) {
        EventHandler<TestPayload> handler = mock(EventHandler.class);
        when(handler.getSupportedEventType()).thenReturn(eventType);
        when(handler.getPayloadType()).thenReturn(TestPayload.class);
        return handler;
    }

    private String buildEnvelopeJson(UUID eventId, String eventType, String correlationId, String payloadJson) {
        return String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"%s\",\"occurredAt\":\"%s\",\"correlationId\":\"%s\",\"payload\":%s}",
                eventId, eventType, Instant.now(), correlationId, payloadJson);
    }

    /**
     * Minimal test-only payload type used to verify deserialization and routing.
     */
    public static class TestPayload {
        private String value;

        public TestPayload() {
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}