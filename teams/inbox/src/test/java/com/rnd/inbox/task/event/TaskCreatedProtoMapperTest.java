package com.rnd.inbox.task.event;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Struct;
import com.google.protobuf.util.Timestamps;
import com.rnd.inbox.event.ExtractionProvenance;
import com.rnd.inbox.event.SourceType;
import com.rnd.inbox.event.TaskCreatedEvent;
import com.rnd.inbox.event.TaskRoleContext;
import com.rnd.inbox.event.TaskStatus;
import com.rnd.inbox.event.TaskTier;
import com.rnd.inbox.shared.event.EventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskCreatedProtoMapperTest {

    private final TaskCreatedProtoMapper mapper = new TaskCreatedProtoMapper(new ObjectMapper());

    @Test
    void supportedType_returnsTaskCreated() {
        assertThat(mapper.supportedType()).isEqualTo(EventType.TASK_CREATED);
    }

    @Test
    void toProto_mapsAllFieldsWhenPresent() {
        UUID taskId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID createdBy = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-07T10:15:30Z");
        Instant deadline = Instant.parse("2026-09-10T12:00:00Z");

        Map<String, Object> payload = fullPayload(taskId, tenantId, projectId, createdBy, createdAt, deadline);

        TaskCreatedEvent event = mapper.toProto(payload);

        assertThat(event.getId()).isEqualTo(taskId.toString());
        assertThat(event.getTitle()).isEqualTo("Write the quarterly report");
        assertThat(event.getDescription()).isEqualTo("Summarize Q3 numbers");
        assertThat(event.getTenantId()).isEqualTo(tenantId.toString());
        assertThat(event.getProjectId()).isEqualTo(projectId.toString());
        assertThat(event.getSourceType()).isEqualTo(SourceType.SOURCE_TYPE_GITHUB_ISSUE);
        assertThat(event.getSourceRef()).isEqualTo("github:owner/repo:issue:42");
        assertThat(event.getIsAvailable()).isTrue();
        assertThat(event.getTier()).isEqualTo(TaskTier.TASK_TIER_STRATEGIC);
        assertThat(event.getRoleContext()).isEqualTo(TaskRoleContext.TASK_ROLE_CONTEXT_MANAGER);
        assertThat(event.getUrgency()).isEqualTo(4);
        assertThat(event.getEffortEstimateMinutes()).isEqualTo(90);
        assertThat(Timestamps.toMillis(event.getDeadline())).isEqualTo(deadline.toEpochMilli());
        assertThat(event.getStatus()).isEqualTo(TaskStatus.TASK_STATUS_IN_PROGRESS);
        assertThat(event.getExtractionProvenance()).isEqualTo(ExtractionProvenance.EXTRACTION_PROVENANCE_ML_INFERRED);
        assertThat(event.getConfidence()).isEqualTo(0.87f);
        assertThat(event.getEffortEstimateConfidence()).isEqualTo(0.62f);
        assertThat(event.getSuggestedOwnerUserId()).isEqualTo(payload.get("suggestedOwnerUserId"));
        assertThat(event.getSuggestedOwnerConfidence()).isEqualTo(0.73f);
        assertThat(event.getCreatedBy()).isEqualTo(createdBy.toString());
        assertThat(Timestamps.toMillis(event.getCreatedAt())).isEqualTo(createdAt.toEpochMilli());
    }

    @Test
    void toProto_omitsOptionalFieldsWhenAbsentFromPayload() {
        Map<String, Object> payload = minimalPayload();

        TaskCreatedEvent event = mapper.toProto(payload);

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
        assertThat(event.hasCreatedBy()).isFalse();
        assertThat(event.hasMetadata()).isFalse();
    }

    @Test
    void toProto_whenDescriptionAndSourceRefAreNull_sendsEmptyStrings() {
        Map<String, Object> payload = minimalPayload();
        payload.put("description", null);
        payload.put("sourceRef", null);

        TaskCreatedEvent event = mapper.toProto(payload);

        assertThat(event.getDescription()).isEmpty();
        assertThat(event.getSourceRef()).isEmpty();
    }

    @Test
    void toProto_whenDescriptionAndSourceRefAreAbsent_sendsEmptyStrings() {
        Map<String, Object> payload = minimalPayload();
        payload.remove("description");
        payload.remove("sourceRef");

        TaskCreatedEvent event = mapper.toProto(payload);

        assertThat(event.getDescription()).isEmpty();
        assertThat(event.getSourceRef()).isEmpty();
    }

    @Test
    void toProto_mapsNewlyAddedDriveSourceType() {
        Map<String, Object> payload = minimalPayload();
        payload.put("sourceType", "DRIVE");

        TaskCreatedEvent event = mapper.toProto(payload);

        assertThat(event.getSourceType()).isEqualTo(SourceType.SOURCE_TYPE_DRIVE);
    }

    @Test
    void toProto_mapsZoomSourceType() {
        Map<String, Object> payload = minimalPayload();
        payload.put("sourceType", "ZOOM");

        TaskCreatedEvent event = mapper.toProto(payload);

        assertThat(event.getSourceType()).isEqualTo(SourceType.SOURCE_TYPE_ZOOM);
    }

    @Test
    void toProto_mapsBigBlueButtonSourceType() {
        Map<String, Object> payload = minimalPayload();
        payload.put("sourceType", "BIGBLUEBUTTON");

        TaskCreatedEvent event = mapper.toProto(payload);

        assertThat(event.getSourceType()).isEqualTo(SourceType.SOURCE_TYPE_BIGBLUEBUTTON);
    }

    @Test
    void toProto_stillMapsMeetingSourceType() {
        Map<String, Object> payload = minimalPayload();
        payload.put("sourceType", "MEETING");

        TaskCreatedEvent event = mapper.toProto(payload);

        assertThat(event.getSourceType()).isEqualTo(SourceType.SOURCE_TYPE_MEETING);
    }

    @Test
    void toProto_throwsForSourceTypeNotYetInProtoSchema() {
        Map<String, Object> payload = minimalPayload();
        payload.put("sourceType", "NOT_A_REAL_VALUE");

        assertThatThrownBy(() -> mapper.toProto(payload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toProto_convertsMetadataMapToStruct() {
        Map<String, Object> payload = minimalPayload();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "slack-thread");
        metadata.put("confidenceHint", 0.75);
        metadata.put("reviewed", false);
        payload.put("metadata", metadata);

        TaskCreatedEvent event = mapper.toProto(payload);

        Struct struct = event.getMetadata();
        assertThat(struct.getFieldsMap().get("source").getStringValue()).isEqualTo("slack-thread");
        assertThat(struct.getFieldsMap().get("confidenceHint").getNumberValue()).isEqualTo(0.75);
        assertThat(struct.getFieldsMap().get("reviewed").getBoolValue()).isFalse();
    }

    private Map<String, Object> fullPayload(UUID taskId, UUID tenantId, UUID projectId, UUID createdBy,
                                             Instant createdAt, Instant deadline) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId.toString());
        payload.put("title", "Write the quarterly report");
        payload.put("description", "Summarize Q3 numbers");
        payload.put("tenantId", tenantId.toString());
        payload.put("projectId", projectId.toString());
        payload.put("sourceType", "GITHUB_ISSUE");
        payload.put("sourceRef", "github:owner/repo:issue:42");
        payload.put("available",true);
        payload.put("tier", "STRATEGIC");
        payload.put("roleContext", "MANAGER");
        payload.put("urgency", 4);
        payload.put("effortEstimateMinutes", 90);
        payload.put("deadline", deadline.toString());
        payload.put("status", "IN_PROGRESS");
        payload.put("extractionProvenance", "ML_INFERRED");
        payload.put("confidence", 0.87f);
        payload.put("effortEstimateConfidence", 0.62f);
        payload.put("suggestedOwnerUserId", UUID.randomUUID().toString());
        payload.put("suggestedOwnerConfidence", 0.73f);
        payload.put("createdBy", createdBy.toString());
        payload.put("createdAt", createdAt.toString());
        return payload;
    }

    private Map<String, Object> minimalPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", UUID.randomUUID().toString());
        payload.put("title", "Minimal task");
        payload.put("description", "No optional fields set");
        payload.put("tenantId", UUID.randomUUID().toString());
        payload.put("sourceType", "MANUAL");
        payload.put("sourceRef", "manual:1");
        payload.put("available",false);
        payload.put("status", "NEW");
        payload.put("extractionProvenance", "MANUAL");
        payload.put("createdAt", Instant.parse("2026-09-07T08:00:00Z").toString());
        return payload;
    }
}
