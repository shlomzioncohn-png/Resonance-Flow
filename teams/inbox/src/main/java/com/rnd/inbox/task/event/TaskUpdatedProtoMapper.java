package com.rnd.inbox.task.event;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.model.TaskTier;
import com.rnd.inbox.task.security.TaskRoleContext;
import com.google.protobuf.util.Timestamps;
import com.rnd.inbox.event.ExtractionProvenance;
import com.rnd.inbox.event.TaskUpdatedEvent;
import com.rnd.inbox.shared.event.EventType;
import com.rnd.inbox.shared.event.ProtoEventMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class TaskUpdatedProtoMapper implements ProtoEventMapper<TaskUpdatedEvent> {

    @Override
    public EventType supportedType() {
        return EventType.TASK_UPDATED;
    }

    @Override
    public TaskUpdatedEvent toProto(Map<String, Object> payload) {
        TaskUpdatedEvent.Builder builder = TaskUpdatedEvent.newBuilder()
                .setTaskId((String) payload.get("taskId"))
                .setTenantId((String) payload.get("tenantId"))
                .setTitle((String) payload.get("title"))
                .setIsAvailable((Boolean) payload.get("available"))
                .setSourceType(toProtoSourceType((String) payload.get("sourceType")))
                .setStatus(toProtoStatus((String) payload.get("status")))
                .setExtractionProvenance(toProtoProvenance((String) payload.get("extractionProvenance")))
                .setUpdatedAt(toTimestamp((String) payload.get("updatedAt")));

        setIfPresent(payload, "description", v -> builder.setDescription((String) v));
        setIfPresent(payload, "sourceRef", v -> builder.setSourceRef((String) v));
        setIfPresent(payload, "projectId", v -> builder.setProjectId((String) v));
        setIfPresent(payload, "tier", v -> builder.setTier(toProtoTier((String) v)));
        setIfPresent(payload, "roleContext", v -> builder.setRoleContext(toProtoRoleContext((String) v)));
        setIfPresent(payload, "urgency", v -> builder.setUrgency((Integer) v));
        setIfPresent(payload, "effortEstimateMinutes", v -> builder.setEffortEstimateMinutes((Integer) v));
        setIfPresent(payload, "deadline", v -> builder.setDeadline(toTimestamp((String) v)));
        setIfPresent(payload, "confidence", v -> builder.setConfidence(((Number) v).floatValue()));
        setIfPresent(payload, "effortEstimateConfidence", v -> builder.setEffortEstimateConfidence(((Number) v).floatValue()));
        setIfPresent(payload, "suggestedOwnerUserId", v -> builder.setSuggestedOwnerUserId((String) v));
        setIfPresent(payload, "suggestedOwnerConfidence", v -> builder.setSuggestedOwnerConfidence(((Number) v).floatValue()));
        setIfPresent(payload, "ownerId", v -> builder.setOwnerId((String) v));

        return builder.build();
    }

    private void setIfPresent(Map<String, Object> payload, String key, Consumer<Object> setter) {
        Object value = payload.get(key);
        if (value != null) {
            setter.accept(value);
        }
    }

    private com.rnd.inbox.event.SourceType toProtoSourceType(String javaEnumName) {
        return com.rnd.inbox.event.SourceType.valueOf("SOURCE_TYPE_" + javaEnumName);
    }

    private com.rnd.inbox.event.TaskTier toProtoTier(String javaEnumName) {
        return com.rnd.inbox.event.TaskTier.valueOf("TASK_TIER_" + javaEnumName);
    }

    private com.rnd.inbox.event.TaskRoleContext toProtoRoleContext(String javaEnumName) {
        return com.rnd.inbox.event.TaskRoleContext.valueOf("TASK_ROLE_CONTEXT_" + javaEnumName);
    }

    private com.rnd.inbox.event.TaskStatus toProtoStatus(String javaEnumName) {
        return com.rnd.inbox.event.TaskStatus.valueOf("TASK_STATUS_" + javaEnumName);
    }

    private ExtractionProvenance toProtoProvenance(String javaEnumName) {
        return ExtractionProvenance.valueOf("EXTRACTION_PROVENANCE_" + javaEnumName);
    }

    private com.google.protobuf.Timestamp toTimestamp(String isoInstant) {
        return Timestamps.fromMillis(Instant.parse(isoInstant).toEpochMilli());
    }
}
