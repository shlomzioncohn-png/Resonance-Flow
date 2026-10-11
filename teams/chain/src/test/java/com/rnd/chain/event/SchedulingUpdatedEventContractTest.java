package com.rnd.chain.event;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.rnd.inbox.event.SchedulingUpdatedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the task.scheduling.updated wire contract that Inbox's consumer decodes by field number. */
class SchedulingUpdatedEventContractTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();

    @Test
    void fieldNumbersNamesAndTypes_matchTheRegisteredContract() {
        assertField(1, "event_id", FieldDescriptor.Type.STRING);
        assertField(2, "task_id", FieldDescriptor.Type.STRING);
        assertField(3, "available", FieldDescriptor.Type.BOOL);
        assertField(4, "priority_score", FieldDescriptor.Type.DOUBLE);
        assertEquals(4, SchedulingUpdatedEvent.getDescriptor().getFields().size());
    }

    @Test
    void chainMessage_roundTripsOverTheWire() throws Exception {
        TaskSchedulingUpdatedEvent event = new TaskSchedulingUpdatedEvent(EVENT_ID, TASK_ID, true, 7.5);

        byte[] wire = KafkaSchedulingUpdatePublisher.toProto(event).toByteArray();
        SchedulingUpdatedEvent decoded = SchedulingUpdatedEvent.parseFrom(wire);

        assertEquals(EVENT_ID.toString(), decoded.getEventId());
        assertEquals(TASK_ID.toString(), decoded.getTaskId());
        assertTrue(decoded.getAvailable());
        assertEquals(7.5, decoded.getPriorityScore());
    }

    @Test
    void unscoredTask_omitsPriorityScoreOnTheWire() throws Exception {
        TaskSchedulingUpdatedEvent event = new TaskSchedulingUpdatedEvent(EVENT_ID, TASK_ID, false, null);

        SchedulingUpdatedEvent decoded = SchedulingUpdatedEvent
                .parseFrom(KafkaSchedulingUpdatePublisher.toProto(event).toByteArray());

        assertFalse(decoded.hasPriorityScore());
        assertTrue(decoded.hasAvailable());
        assertFalse(decoded.getAvailable());
    }

    private static void assertField(int number, String name, FieldDescriptor.Type type) {
        FieldDescriptor actual = SchedulingUpdatedEvent.getDescriptor().findFieldByNumber(number);
        assertNotNull(actual, "missing field #" + number);
        assertEquals(name, actual.getName());
        assertEquals(type, actual.getType());
    }
}
