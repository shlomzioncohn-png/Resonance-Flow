package com.rnd.chain.events.payloads;

import java.util.UUID;

/**
 * Payload for the task.scheduling.updated event.
 *
 * TODO: taskId is UUID for now, matching the placeholder used across
 * DependencyGraphService/ConstraintService until Inbox.TaskId is finalized.
 *
 * TODO: priorityScore's type/range/update semantics are not yet agreed
 * with Inbox - see Inbox_Kafka_Ingestion_Contract.md section 4 and
 * PLAN.md decision D5. Do not treat this shape as a settled cross-team
 * contract; it is infrastructure-ready, not contract-approved.
 *
 * A record on purpose: an immutable data holder with no inheritance and
 * no unusual (de)serialization needs - Jackson's built-in record support
 * serializes it with the same field names (taskId, isAvailable,
 * priorityScore) that the previous hand-written getters produced, so this
 * changes no wire format.
 */
public record TaskSchedulingUpdatedPayload(UUID taskId, boolean isAvailable, int priorityScore) {
}
