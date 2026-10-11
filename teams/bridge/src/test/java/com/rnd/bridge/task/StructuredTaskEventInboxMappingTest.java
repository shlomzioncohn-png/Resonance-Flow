package com.rnd.bridge.task;

import com.google.protobuf.util.Timestamps;
import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.inbox.event.StructuredTaskEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirrors - does not call - the enum-prefix-stripping and Timestamp-to-ISO conversion that
 * Inbox's own {@code StructuredTaskProtoMapper.toDto} applies on the consuming side
 * (teams/inbox/src/main/java/com/rnd/inbox/structuredtask/StructuredTaskProtoMapper.java, as of
 * commit a5fe93cf). Bridge has no dependency on Inbox's module and shouldn't take one just for a
 * test, so the two private helper methods below are copied, not imported - if Inbox ever changes
 * this logic, this test diverging from it silently is the risk accepted for not sharing code
 * across the team boundary.
 * <p>
 * The point of this test is the seam {@link StructuredTaskEventWireFormatTest} doesn't cover:
 * given exactly what {@link StructuredTaskEventMapper} puts on the wire, does Inbox's own
 * unprefixing/conversion logic recover the original domain values.
 */
class StructuredTaskEventInboxMappingTest {

    @Test
    void inboxsUnprefixingRecoversTheOriginalSourceTypeStatusAndDeadline() {
        Instant deadline = Instant.parse("2026-11-01T12:34:56Z");
        Task task = new Task(
                "11111111-1111-1111-1111-111111111111", null,
                "Follow up with Acme", null,
                TaskStatus.NEW, null, null,
                null, ExtractionProvenance.ETL, null,
                SourceType.CRM_DEAL, "crm:D-1",
                null, deadline, null, null);

        StructuredTaskEvent event = StructuredTaskEventMapper.from(task);

        assertThat(fromSourceType(event.getSourceType())).isEqualTo("CRM_DEAL");
        assertThat(fromTaskStatus(event.getStatus())).isEqualTo("NEW");
        assertThat(toIsoInstant(event.getDeadline())).isEqualTo(deadline.toString());
    }

    // --- copied from StructuredTaskProtoMapper, see class Javadoc ---

    private String fromSourceType(com.rnd.inbox.event.SourceType sourceType) {
        return sourceType.name().substring("SOURCE_TYPE_".length());
    }

    private String fromTaskStatus(com.rnd.inbox.event.TaskStatus status) {
        return status.name().substring("TASK_STATUS_".length());
    }

    private String toIsoInstant(com.google.protobuf.Timestamp timestamp) {
        return Instant.ofEpochMilli(Timestamps.toMillis(timestamp)).toString();
    }
}
