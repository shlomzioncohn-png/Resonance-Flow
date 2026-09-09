package com.rnd.inbox.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.Timestamps;
import com.rnd.inbox.event.TaskCreatedEvent;
import com.rnd.inbox.event.ExtractionProvenance;
import com.rnd.inbox.shared.event.EventType;
import com.rnd.inbox.shared.event.ProtoEventMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class TaskCreatedProtoMapper implements ProtoEventMapper<TaskCreatedEvent> {

    private final ObjectMapper objectMapper;

    public TaskCreatedProtoMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public EventType supportedType() {
        return EventType.TASK_CREATED;
    }

    @Override
    public TaskCreatedEvent toProto(Map<String, Object> payload) {
        TaskCreatedEvent.Builder builder = TaskCreatedEvent.newBuilder()
                .setId((String) payload.get("taskId"))
                .setTitle((String) payload.get("title"))
                .setDescription((String) payload.get("description"))
                .setTenantId((String) payload.get("tenantId"))
                .setSourceType(toProtoSourceType((String) payload.get("sourceType")))
                .setSourceRef((String) payload.get("sourceRef"))
                .setIsAvailable((Boolean) payload.get("available"))
                .setStatus(toProtoStatus((String) payload.get("status")))
                .setExtractionProvenance(toProtoProvenance((String) payload.get("extractionProvenance")))
                .setCreatedAt(toTimestamp((String) payload.get("createdAt")));

        setIfPresent(payload, "projectId", v -> builder.setProjectId((String) v));
        setIfPresent(payload, "tier", v -> builder.setTier(toProtoTier((String) v)));
        setIfPresent(payload, "roleContext", v -> builder.setRoleContext(toProtoRoleContext((String) v)));
        setIfPresent(payload, "urgency", v -> builder.setUrgency((Integer) v));
        setIfPresent(payload, "effortEstimateMinutes", v -> builder.setEffortEstimateMinutes((Integer) v));
        setIfPresent(payload, "deadline", v -> builder.setDeadline(toTimestamp((String) v)));
        setIfPresent(payload, "confidence", v -> builder.setConfidence(((Number) v).floatValue()));
        setIfPresent(payload, "effortEstimateConfidence", v -> builder.setEffortEstimateConfidence(((Number) v).floatValue()));
        setIfPresent(payload, "createdBy", v -> builder.setCreatedBy((String) v));
        setIfPresent(payload, "metadata", v -> builder.setMetadata(toStruct(v)));

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

    private Struct toStruct(Object metadataMap) {
        try {
            String json = objectMapper.writeValueAsString(metadataMap);
            Struct.Builder structBuilder = Struct.newBuilder();
            JsonFormat.parser().merge(json, structBuilder);
            return structBuilder.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to convert metadata to protobuf Struct", e);
        }
    }
}
