package com.rnd.bridge.protobuf;

import com.google.protobuf.Timestamp;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.Task;

import java.time.Instant;

/**
 * Converter for transforming Bridge Task (internal) to Protobuf Task (generated).
 */
public class TaskProtobufConverter {

    /**
     * Converts a Bridge Task to a Protobuf Task.
     *
     * @param bridgeTask the Bridge Task to convert
     * @return the Protobuf Task
     */
    public static com.rnd.bridge.dto.Task toProtobufTask(com.rnd.bridge.task.model.Task bridgeTask) {
        if (bridgeTask == null) {
            return null;
        }

        com.rnd.bridge.dto.Task.Builder builder = com.rnd.bridge.dto.Task.newBuilder();

        // Map id from organizationId
        if (bridgeTask.organizationId() != null) {
            builder.setId(bridgeTask.organizationId());
        }

        // Map title
        if (bridgeTask.title() != null) {
            builder.setTitle(bridgeTask.title());
        }

        // Map description (optional)
        if (bridgeTask.description() != null) {
            builder.setDescription(bridgeTask.description());
        }

        // Map source_type
        builder.setSourceType(mapSourceType(bridgeTask.sourceType()));

        // Map source_ref
        if (bridgeTask.sourceRef() != null) {
            builder.setSourceRef(bridgeTask.sourceRef());
        }

        // Map owner (not in Bridge Task, leave empty)
        // builder.setOwner(...);

        // Map role_context
        if (bridgeTask.roleContext() != null) {
            builder.setRoleContext(bridgeTask.roleContext().name());
        }

        // Map tier (optional)
        if (bridgeTask.tier() != null) {
            builder.setTier(mapTier(bridgeTask.tier()));
        }

        // Map urgency (optional)
        if (bridgeTask.urgency() != null) {
            builder.setUrgency(bridgeTask.urgency());
        }

        // Map effort_estimate_minutes (optional)
        if (bridgeTask.effortEstimateMinutes() != null) {
            builder.setEffortEstimateMinutes(bridgeTask.effortEstimateMinutes());
        }

        // Map deadline (optional)
        if (bridgeTask.deadline() != null) {
            builder.setDeadline(instantToTimestamp(bridgeTask.deadline()));
        }

        // Map parent_task_id (not in Bridge Task, leave empty)
        // builder.setParentTaskId(...);

        // Map depends_on (not in Bridge Task, leave empty)
        // builder.addAllDependsOn(...);

        // Map constraints (not in Bridge Task, leave empty)
        // builder.addAllConstraints(...);

        // Map confidence (optional)
        if (bridgeTask.confidence() != null) {
            builder.setConfidence(bridgeTask.confidence());
        }

        // Map extraction_provenance
        builder.setExtractionProvenance(mapExtractionProvenance(bridgeTask.extractionProvenance()));

        // Map status
        builder.setStatus(mapTaskStatus(bridgeTask.status()));

        return builder.build();
    }

    private static com.rnd.bridge.dto.SourceType mapSourceType(com.rnd.bridge.task.model.SourceType sourceType) {
        if (sourceType == null) {
            return com.rnd.bridge.dto.SourceType.SOURCE_TYPE_UNSPECIFIED;
        }
        return switch (sourceType) {
            case GITHUB_ISSUE -> com.rnd.bridge.dto.SourceType.SOURCE_TYPE_GITHUB;
            case JIRA_ISSUE -> com.rnd.bridge.dto.SourceType.SOURCE_TYPE_UNSPECIFIED; // Safe fallback
            case CRM_DEAL -> com.rnd.bridge.dto.SourceType.SOURCE_TYPE_UNSPECIFIED; // Safe fallback
            default -> com.rnd.bridge.dto.SourceType.SOURCE_TYPE_UNSPECIFIED;
        };
    }

    private static com.rnd.bridge.dto.Tier mapTier(com.rnd.bridge.task.model.Tier tier) {
        if (tier == null) {
            return com.rnd.bridge.dto.Tier.TIER_UNSPECIFIED;
        }
        return switch (tier) {
            case STRATEGIC -> com.rnd.bridge.dto.Tier.TIER_1;
            case OPERATIONAL -> com.rnd.bridge.dto.Tier.TIER_2;
            case TACTICAL -> com.rnd.bridge.dto.Tier.TIER_3;
            default -> com.rnd.bridge.dto.Tier.TIER_UNSPECIFIED;
        };
    }

    private static com.rnd.bridge.dto.TaskStatus mapTaskStatus(com.rnd.bridge.task.model.TaskStatus status) {
        if (status == null) {
            return com.rnd.bridge.dto.TaskStatus.TASK_STATUS_UNSPECIFIED;
        }
        return switch (status) {
            case NEW -> com.rnd.bridge.dto.TaskStatus.TASK_STATUS_OPEN;
            case DONE -> com.rnd.bridge.dto.TaskStatus.TASK_STATUS_CLOSED;
            case IN_PROGRESS -> com.rnd.bridge.dto.TaskStatus.TASK_STATUS_IN_PROGRESS;
            default -> com.rnd.bridge.dto.TaskStatus.TASK_STATUS_UNSPECIFIED;
        };
    }

    private static com.rnd.bridge.dto.ExtractionProvenance mapExtractionProvenance(com.rnd.bridge.task.model.ExtractionProvenance provenance) {
        if (provenance == null) {
            return com.rnd.bridge.dto.ExtractionProvenance.EXTRACTION_PROVENANCE_UNSPECIFIED;
        }
        return switch (provenance) {
            case ETL -> com.rnd.bridge.dto.ExtractionProvenance.EXTRACTION_PROVENANCE_API;
            case ML_INFERRED -> com.rnd.bridge.dto.ExtractionProvenance.EXTRACTION_PROVENANCE_API; // Map ML_INFERRED to API
            case MANUAL -> com.rnd.bridge.dto.ExtractionProvenance.EXTRACTION_PROVENANCE_WEBHOOK; // Map MANUAL to WEBHOOK
        };
    }

    private static Timestamp instantToTimestamp(Instant instant) {
        if (instant == null) {
            return null;
        }
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
