package com.rnd.inbox.task.event;
import com.google.protobuf.util.Timestamps;
import com.rnd.inbox.event.ExtractionProvenance;
import com.rnd.inbox.event.SourceType;
import com.rnd.inbox.event.TaskRoleContext;
import com.rnd.inbox.event.TaskStatus;
import com.rnd.inbox.event.TaskTier;
import com.rnd.inbox.event.TaskUpdatedEvent;
import com.rnd.inbox.shared.event.EventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskUpdatedProtoMapperTest {

    private final TaskUpdatedProtoMapper mapper = new TaskUpdatedProtoMapper();

    @Test
    void supportedType_returnsTaskUpdated() {
        assertThat(mapper.supportedType()).isEqualTo(EventType.TASK_UPDATED);
    }

    @Test
    void toProto_mapsAllFieldsWhenPresent() {
        UUID taskId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID suggestedOwnerUserId = UUID.randomUUID();
        Instant updatedAt = Instant.parse("2026-09-09T10:15:30Z");
        Instant deadline = Instant.parse("2026-09-12T12:00:00Z");

        Map<String, Object> payload = fullPayload(taskId, tenantId, projectId, suggestedOwnerUserId, updatedAt, deadline);

        TaskUpdatedEvent event = mapper.toProto(payload);

        assertThat(event.getTaskId()).isEqualTo(taskId.toString());
        assertThat(event.getTenantId()).isEqualTo(tenantId.toString());
        assertThat(event.getProjectId()).isEqualTo(projectId.toString());
        assertThat(event.getTitle()).isEqualTo("Prepare release notes");
        assertThat(event.getDescription()).isEqualTo("Summarize what shipped this sprint");
        assertThat(event.getIsAvailable()).isTrue();
        assertThat(event.getSourceType()).isEqualTo(SourceType.SOURCE_TYPE_GITHUB_PR);
        assertThat(event.getSourceRef()).isEqualTo("github:owner/repo:pr:7");
        assertThat(event.getTier()).isEqualTo(TaskTier.TASK_TIER_TACTICAL);
        assertThat(event.getRoleContext()).isEqualTo(TaskRoleContext.TASK_ROLE_CONTEXT_DEVELOPER);
        assertThat(event.getUrgency()).isEqualTo(3);
        assertThat(event.getEffortEstimateMinutes()).isEqualTo(45);
        assertThat(Timestamps.toMillis(event.getDeadline())).isEqualTo(deadline.toEpochMilli());
        assertThat(event.getStatus()).isEqualTo(TaskStatus.TASK_STATUS_IN_REVIEW);
        assertThat(event.getExtractionProvenance()).isEqualTo(ExtractionProvenance.EXTRACTION_PROVENANCE_MANUAL);
        assertThat(event.getConfidence()).isEqualTo(0.91f);
        assertThat(event.getEffortEstimateConfidence()).isEqualTo(0.5f);
        assertThat(event.getSuggestedOwnerUserId()).isEqualTo(suggestedOwnerUserId.toString());
        assertThat(event.getSuggestedOwnerConfidence()).isEqualTo(0.73f);
        assertThat(Timestamps.toMillis(event.getUpdatedAt())).isEqualTo(updatedAt.toEpochMilli());
    }

    @Test
    void toProto_omitsOptionalFieldsWhenAbsentFromPayload() {
        Map<String, Object> payload = minimalPayload();

        TaskUpdatedEvent event = mapper.toProto(payload);

        assertThat(event.hasProjectId()).isFalse();
        assertThat(event.hasTier()).isFalse();
        assertThat(event.hasRoleContext()).isFalse();
        assertThat(event.hasUrgency()).isFalse();
        assertThat(event.hasEffortEstimateMinutes()).isFalse();
        assertThat(event.hasDeadline()).isFalse();
        assertThat(event.hasConfidence()).isFalse();
        assertThat(event.hasEffortEstimateConfidence()).isFalse();
        assertThat(event.hasSuggestedOwnerUserId()).isFalse();
        assertThat(event.hasSuggestedOwnerConfidence()).isFalse();
    }

    @Test
    void toProto_mapsNewlyAddedDriveSourceType() {
        Map<String, Object> payload = minimalPayload();
        payload.put("sourceType", "DRIVE");

        TaskUpdatedEvent event = mapper.toProto(payload);

        assertThat(event.getSourceType()).isEqualTo(SourceType.SOURCE_TYPE_DRIVE);
    }

    @Test
    void toProto_throwsForSourceTypeNotYetInProtoSchema() {
        Map<String, Object> payload = minimalPayload();
        payload.put("sourceType", "NOT_A_REAL_VALUE");

        assertThatThrownBy(() -> mapper.toProto(payload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toProto_throwsForStatusNotYetInProtoSchema() {
        Map<String, Object> payload = minimalPayload();
        payload.put("status", "NOT_A_REAL_VALUE");

        assertThatThrownBy(() -> mapper.toProto(payload))
                .isInstanceOf(IllegalArgumentException.class);
    }


    @Test
    void toProto_throwsForTheOldPartialSchedulingUpdatePayload() {
        Map<String, Object> schedulingOnlyPayload = new LinkedHashMap<>();
        schedulingOnlyPayload.put("taskId", UUID.randomUUID().toString());
        schedulingOnlyPayload.put("isAvailable", true);
        schedulingOnlyPayload.put("priorityScore", 7.5f);

        assertThatThrownBy(() -> mapper.toProto(schedulingOnlyPayload))
                .isInstanceOf(NullPointerException.class);
    }

    private Map<String, Object> fullPayload(UUID taskId, UUID tenantId, UUID projectId, UUID suggestedOwnerUserId,
                                             Instant updatedAt, Instant deadline) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId.toString());
        payload.put("tenantId", tenantId.toString());
        payload.put("projectId", projectId.toString());
        payload.put("title", "Prepare release notes");
        payload.put("description", "Summarize what shipped this sprint");
        payload.put("available", true);
        payload.put("sourceType", "GITHUB_PR");
        payload.put("sourceRef", "github:owner/repo:pr:7");
        payload.put("tier", "TACTICAL");
        payload.put("roleContext", "DEVELOPER");
        payload.put("urgency", 3);
        payload.put("effortEstimateMinutes", 45);
        payload.put("deadline", deadline.toString());
        payload.put("status", "IN_REVIEW");
        payload.put("extractionProvenance", "MANUAL");
        payload.put("confidence", 0.91f);
        payload.put("effortEstimateConfidence", 0.5f);
        payload.put("suggestedOwnerUserId", suggestedOwnerUserId.toString());
        payload.put("suggestedOwnerConfidence", 0.73f);
        payload.put("updatedAt", updatedAt.toString());
        return payload;
    }

    private Map<String, Object> minimalPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", UUID.randomUUID().toString());
        payload.put("tenantId", UUID.randomUUID().toString());
        payload.put("title", "Minimal update");
        payload.put("description", "No optional fields set");
        payload.put("available", false);
        payload.put("sourceType", "MANUAL");
        payload.put("sourceRef", "manual:1");
        payload.put("status", "NEW");
        payload.put("extractionProvenance", "MANUAL");
        payload.put("updatedAt", Instant.parse("2026-09-09T08:00:00Z").toString());
        return payload;
    }
}
