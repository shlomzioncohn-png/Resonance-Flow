package com.rnd.chain.events.payloads;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Payload for the task.scheduling.updated event Chain publishes to Inbox.
 *
 * Shape confirmed directly against Inbox's own SchedulingEventDto (teams/inbox): flat JSON (no
 * envelope), fields eventId/taskId/isAvailable/priorityScore, priorityScore a nullable Double in
 * range 1.0-10.0 rather than the earlier placeholder int - a primitive int can't represent "not
 * scored yet" at all. Was: {@code int priorityScore} (PLAN.md decision D5, "infrastructure-ready,
 * not contract-approved") - now resolved against Inbox's real, implemented contract.
 *
 * {@code @JsonInclude(NON_NULL)}: Inbox's contract is "omitting priorityScore on an update leaves
 * the stored value unchanged" - so when Chain hasn't scored a task yet, priorityScore must be left
 * out of the JSON entirely, not sent as an explicit {@code null}. Those are different things on
 * the wire, and only omission matches Inbox's documented behavior.
 *
 * TODO: taskId is UUID for now, matching the placeholder used across
 * DependencyGraphService/ConstraintService until Inbox.TaskId is finalized.
 *
 * A record on purpose: an immutable data holder with no inheritance and
 * no unusual (de)serialization needs beyond the inclusion rule above -
 * Jackson's built-in record support serializes it with the same field
 * names (eventId, taskId, isAvailable, priorityScore) the wire contract expects.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskSchedulingUpdatedPayload(UUID eventId, UUID taskId, boolean isAvailable, Double priorityScore) {
}
