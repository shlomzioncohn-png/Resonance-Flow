package com.rnd.chain.event;

import com.google.protobuf.Descriptors.FieldDescriptor;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks Chain's task.scheduling.updated wire output against a copy of Inbox's own proto. */
class SchedulingUpdatedEventContractTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();

    @Test
    void fieldNumbersNamesAndTypes_matchInboxProto() {
        var chain = SchedulingUpdatedEvent.getDescriptor().getFields();
        var inbox = com.rnd.inbox.event.SchedulingUpdatedEvent.getDescriptor().getFields();

        assertEquals(inbox.size(), chain.size());
        for (FieldDescriptor expected : inbox) {
            FieldDescriptor actual = SchedulingUpdatedEvent.getDescriptor().findFieldByNumber(expected.getNumber());
            assertEquals(expected.getName(), actual.getName());
            assertEquals(expected.getType(), actual.getType());
            assertEquals(expected.hasPresence(), actual.hasPresence());
        }
    }

    @Test
    void chainMessage_decodesIntoInboxMessage() throws Exception {
        TaskSchedulingUpdatedEvent event = new TaskSchedulingUpdatedEvent(EVENT_ID, TASK_ID, true, 7.5);

        byte[] wire = KafkaSchedulingUpdatePublisher.toProto(event).toByteArray();
        com.rnd.inbox.event.SchedulingUpdatedEvent decoded =
                com.rnd.inbox.event.SchedulingUpdatedEvent.parseFrom(wire);

        assertEquals(EVENT_ID.toString(), decoded.getEventId());
        assertEquals(TASK_ID.toString(), decoded.getTaskId());
        assertTrue(decoded.getAvailable());
        assertEquals(7.5, decoded.getPriorityScore());
    }

    @Test
    void unscoredTask_omitsPriorityScoreOnTheWire() throws Exception {
        TaskSchedulingUpdatedEvent event = new TaskSchedulingUpdatedEvent(EVENT_ID, TASK_ID, false, null);

        com.rnd.inbox.event.SchedulingUpdatedEvent decoded = com.rnd.inbox.event.SchedulingUpdatedEvent
                .parseFrom(KafkaSchedulingUpdatePublisher.toProto(event).toByteArray());

        assertFalse(decoded.hasPriorityScore());
        assertTrue(decoded.hasAvailable());
        assertFalse(decoded.getAvailable());
    }
}
