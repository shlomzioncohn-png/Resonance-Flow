package com.rnd.bridge.intake.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.intake.lane.GroupStateStore;
import com.rnd.bridge.intake.lane.UnstructuredIntakeLane;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.SlackMessageDeletion;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.parser.SourceRecordGrouper;
import com.rnd.bridge.intake.service.ChatGroupStateReconciler;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.intake.service.SlackSessionScheduler;
import com.rnd.bridge.intake.service.SourceRecordRefMapper;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.scoping.AllowlistScopingFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slack Events API support: the secure HTTP receiving boundary, plus Phase 2's wiring from a
 * verified "message" event into the existing reconciliation/lane/Kafka pipeline.
 *
 * Handles Slack's "url_verification" handshake unconditionally. For "event_callback", a
 * supported plain "message" event (see {@link SlackEventMessageParser}) is turned into a
 * {@link ChatMessage} and reconciled:
 * - threaded messages (threadId() != null) go through {@link ChatGroupStateReconciler#group}
 *   unchanged - same immediate merge+publish semantics the batch/poll path already has for
 *   threads (the delayed-publish design is specific to non-thread time-gap sessions): the
 *   resulting flat SourceRecord list is grouped by correlationId (see {@link SourceRecordGrouper})
 *   and run through the Slack {@link UnstructuredIntakeLane} as one event per group.
 * - non-thread messages go through {@link ChatGroupStateReconciler#reconcileNonThreadedMessage},
 *   which merges+persists the group but withholds the transcript entirely; only this message's own
 *   newly-arrived attachments are landed to MinIO immediately and recorded on GroupState (see
 *   {@link #landAttachments}) - never published to Kafka here. The transcript plus every
 *   landed attachment publish together, exactly once, when the session closes (see
 *   {@code SlackSessionPublisher}, invoked by {@link SlackSessionScheduler}'s inactivity timer).
 * An unsupported/unparseable event shape is still acknowledged with 200 (Slack must not retry
 * it), but never reaches the reconciler - GroupState is left untouched. A supported event that
 * WAS reconciled but whose landing/publish failed gets a non-2xx instead, so Slack retries it
 * rather than believing a failed attempt succeeded.
 * The request body is read as raw bytes (via HttpServletRequest, not a @RequestBody DTO) so
 * SlackSignatureVerifier always checks the exact bytes Slack signed, before any JSON
 * (de)serialization could change them.
 */
@RestController
@RequestMapping("/api/bridge/slack")
public class SlackEventsController {

    private static final Logger log = LoggerFactory.getLogger(SlackEventsController.class);
    private static final String TIMESTAMP_HEADER = "X-Slack-Request-Timestamp";
    private static final String SIGNATURE_HEADER = "X-Slack-Signature";

    private final SlackSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;
    private final SlackEventMessageParser eventMessageParser;
    private final ChatGroupStateReconciler reconciler;
    private final SlackSessionScheduler sessionScheduler;
    private final UnstructuredIntakeLane slackLane;
    private final AllowlistScopingFilter scopingFilter;
    private final SourceRecordRefMapper refMapper;
    private final IntakeLandingService landingService;
    private final GroupStateStore groupStateStore;

    public SlackEventsController(
            SlackSignatureVerifier signatureVerifier, ObjectMapper objectMapper,
            SlackEventMessageParser eventMessageParser, ChatGroupStateReconciler reconciler,
            SlackSessionScheduler sessionScheduler, List<UnstructuredIntakeLane> lanes,
            AllowlistScopingFilter scopingFilter, SourceRecordRefMapper refMapper,
            IntakeLandingService landingService, GroupStateStore groupStateStore) {
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
        this.eventMessageParser = eventMessageParser;
        this.reconciler = reconciler;
        this.sessionScheduler = sessionScheduler;
        this.slackLane = lanes.stream()
                .filter(lane -> lane.sourceType() == RawSourceType.SLACK)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No SLACK UnstructuredIntakeLane configured"));
        this.scopingFilter = scopingFilter;
        this.refMapper = refMapper;
        this.landingService = landingService;
        this.groupStateStore = groupStateStore;
    }

    @PostMapping("/events")
    public ResponseEntity<?> handle(
            @RequestHeader(value = TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            HttpServletRequest request) throws IOException {
        String rawBody = readRawBody(request);

        if (!signatureVerifier.isValid(timestamp, signature, rawBody)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest().build();
        }
        if (root == null || !root.hasNonNull("type")) {
            return ResponseEntity.badRequest().build();
        }

        return switch (root.get("type").asText()) {
            case "url_verification" -> handleUrlVerification(root);
            case "event_callback" -> handleEventCallback(root);
            default -> ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        };
    }

    private ResponseEntity<?> handleUrlVerification(JsonNode root) {
        if (!root.hasNonNull("challenge")) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(new SlackChallengeResponse(root.get("challenge").asText()));
    }

    /**
     * A verified event_callback is acknowledged with 200 only once everything it triggered
     * actually succeeded - see {@link #reconcileAndPublish}. An unsupported/unparseable event
     * shape is still acknowledged with 200 (nothing was attempted, so there is nothing to
     * retry); a supported event whose landing/publish failed gets a non-2xx so Slack retries
     * it. This is safe to retry synchronously: reconciler.reconcileNonThreadedMessage/
     * reconciler.group() already durably persisted the merged GroupState (see
     * GroupStateMutator.mergeInto) before this method is even reached, and a
     * redelivery re-resolves the same messageId to the same group rather than creating a
     * duplicate one.
     */
    private ResponseEntity<?> handleEventCallback(JsonNode root) {
        JsonNode event = root.get("event");
        Optional<ChatMessage> parsed = eventMessageParser.parse(event);
        if (parsed.isPresent()) {
            boolean allPublished = reconcileAndPublish(parsed.get());
            return allPublished
                    ? ResponseEntity.ok().build()
                    : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        Optional<SlackMessageDeletion> deletion = eventMessageParser.parseDeletion(event);
        if (deletion.isPresent()) {
            boolean allPublished = reconcileAndPublishDeletion(deletion.get());
            return allPublished
                    ? ResponseEntity.ok().build()
                    : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.ok().build();
    }

    private boolean reconcileAndPublish(ChatMessage message) {
        if (isThreadReply(message)) {
            // Threaded groups keep today's immediate merge+publish semantics - unaffected by the
            // non-thread delayed-publish design (see class javadoc), and never scheduled.
            return publishGroups(reconciler.group(List.of(message)));
        }
        // A thread root that already has replies carries thread_ts == its own ts on Slack's
        // message_changed payload (SlackEventMessageParser just passes that through - see its
        // javadoc), so isThreadReply(message) is false here even though message.threadId() is
        // non-null. Stripping it back to null before handing off is what lets this message take
        // the branch below instead of the one above: reconcileNonThreadedMessage's own
        // threadRouted case (see its javadoc) is exactly the designed handler for "this id
        // resolves to an existing thread group" and is what actually reaches the channel group
        // via findChannelGroupIfDifferentFromThread/scheduleOrReschedule - routing a root edit
        // into reconciler.group(List.of(message)) above instead would update only the thread
        // group and never touch/reschedule the channel group's own still-living copy of the root.
        ChatMessage nonThreadMessage = withoutSelfThreadId(message);
        // Only newly-arrived attachments are landed here, never published - the transcript stays
        // pending in GroupState for SlackSessionScheduler's inactivity timer to publish, together
        // with every attachment landed during the session (see SlackSessionPublisher). Unless
        // this message's own id actually resolves to an existing thread group (a thread root's
        // edit - its event carries no threadId of its own, see
        // ChatGroupStateReconciler.resolveCurrentGroupId) - that case is routed by the resolved
        // group's kind, not by event shape, and publishes immediately like any other thread group.
        ChatGroupStateReconciler.RealtimeReconcileResult result =
                reconciler.reconcileNonThreadedMessage(nonThreadMessage);
        if (result.threadRouted()) {
            // Channel-group step first, uncaught - see reconcileAndPublishDeletion's javadoc for
            // the full ordering rationale (docs/plans/slack-superseded-fix.md §2b): a root edit
            // may also have updated the channel group's own still-living copy of the root, and
            // scheduling that is never allowed to influence the boolean this method returns.
            if (result.channelGroupState() != null) {
                sessionScheduler.scheduleOrReschedule(result.channelGroupState());
            }
            return publishGroups(result.newAttachmentRecords());
        }
        boolean allLanded = landAttachments(result.newAttachmentRecords());
        // Scheduling is keyed on "does this GroupState still need publishing", not on whether
        // this event was a genuinely-new message - an edit can just as well produce a fresh
        // unpublished revision (superseding an OPEN, CLAIMED, or even already-PUBLISHED group -
        // see GroupStateMutator.rebuild), and that revision must still get a
        // live timer or it would never reach Kafka. scheduleOrReschedule always derives its
        // deadline from GroupState's own message timestamps (see its javadoc), never from when
        // this event arrived, so an edit never artificially extends the session gap - if the
        // computed deadline is already in the past (e.g. editing a long-since-published
        // session), the TaskScheduler simply runs the callback promptly instead of waiting.
        if (!result.groupState().published()) {
            sessionScheduler.scheduleOrReschedule(result.groupState());
        }
        return allLanded;
    }

    /**
     * False for a thread root whose Slack payload sets {@code thread_ts == ts} on itself (every
     * real reply, and a thread_broadcast reply, always has threadId() distinct from its own
     * id() - only a root-with-replies' own message carries them equal). See
     * {@link #withoutSelfThreadId} for why this distinction, not a plain null check, is what
     * decides routing here.
     */
    private static boolean isThreadReply(ChatMessage message) {
        return message.threadId() != null && !message.threadId().equals(message.id());
    }

    /**
     * Strips threadId back to null for exactly the case {@link #isThreadReply} excludes: a
     * thread root whose own message_changed payload carries {@code thread_ts == ts}. Without
     * this, reconciler.reconcileNonThreadedMessage would reject the message outright (it only
     * accepts threadId() == null - see its javadoc) instead of reaching its own threadRouted
     * case, which is the one place that still merges into AND reschedules the channel group's
     * own still-living copy of the root (see findChannelGroupIfDifferentFromThread). Every other
     * message is returned unchanged.
     */
    private static ChatMessage withoutSelfThreadId(ChatMessage message) {
        if (isThreadReply(message) || message.threadId() == null) {
            return message;
        }
        return new ChatMessage(
                message.sourceType(), message.tenantId(), message.chatId(), message.sender(),
                message.timestamp(), message.text(), message.attachments(), message.id(), null);
    }

    /**
     * message_deleted / nested-tombstone path: resolves and removes the message via
     * ChatGroupStateReconciler.removeMessage, then routes by the resolved group's own kind -
     * never by event shape, since a deletion event carries no threadId at all (see
     * SlackMessageDeletion's javadoc). A thread group publishes the (possibly now empty)
     * transcript immediately, exactly like any other thread reconcile; a non-thread group's
     * removal only advances GroupState - the inactivity timer (now possibly sped up via
     * correctionDelay, see SlackSessionScheduler) publishes it later. An unknown/already-removed
     * messageId is a no-op - acknowledged, nothing scheduled or published.
     * <p>
     * A root deletion/edit now also updates the channel group's own still-living copy of the
     * root (see ChatGroupStateReconciler §2 / docs/plans/slack-superseded-fix.md §2b). That
     * channel-group step is always done FIRST, scheduled, uncaught: if
     * {@code sessionScheduler.scheduleOrReschedule} itself throws (an unexpected RuntimeException
     * out of GroupStateStore.update), it propagates straight to Spring's default 500, which is
     * correct - the thread side (attempted next, below) hasn't even run yet, so the whole event
     * must be retried. Scheduling the channel group is purely internal state, never influences
     * this method's returned boolean, exactly like the ordinary non-thread branch above always
     * returns true after a schedule-only call regardless of whether the TaskScheduler
     * registration could be said to have "succeeded". Only the thread group's publish result
     * (the only step with an external Kafka-facing ack) participates in the returned boolean.
     * A retry (Slack redelivering on a non-2xx) is safe on both sides: the channel-group
     * merge/removal reuses the exact same GroupStateMutator.mergeInto/removeFrom primitives the
     * ordinary single-group path already proves idempotent against a redelivered event (see
     * duplicateDeliveryOfTheSameEditDoesNotAdvanceTheRevisionTwice/
     * removeMessageTwiceIsANoOpTheSecondTime), and scheduleOrReschedule always cancels-and-
     * replaces rather than double-scheduling.
     */
    private boolean reconcileAndPublishDeletion(SlackMessageDeletion deletion) {
        ChatGroupStateReconciler.RemovalResult result =
                reconciler.removeMessage(deletion.sourceType(), deletion.tenantId(), deletion.messageId());
        if (!result.removed()) {
            return true;
        }
        if (result.isThreadGroup()) {
            if (result.channelGroupState() != null) {
                sessionScheduler.scheduleOrReschedule(result.channelGroupState());
            }
            List<SourceRecord> records = reconciler.toSourceRecordsForPublishedGroup(result.groupState());
            return publishGroups(records);
        }
        sessionScheduler.scheduleOrReschedule(result.groupState());
        return true;
    }

    // Threaded path only: groups the reconciler's flat SourceRecord list by correlationId (one
    // group per logical item - normally exactly one, the thread's own transcript+attachments)
    // and runs each group through the existing map -> land -> validate -> publish ->
    // afterPublish loop, just against an already-built Stream instead of one read via
    // SourceReader - see AbstractIntakeLane.run(Stream, Instant)'s javadoc. Unlike the batch
    // path (which just freezes/advances a cursor around this same IntakeResult), this real-time
    // caller has no cursor to freeze - the only way to make a rejection retryable is to tell
    // Slack to redeliver the event, so a non-empty IntakeResult.errors() is logged and turned
    // into a false return instead of being silently discarded.
    private boolean publishGroups(List<SourceRecord> records) {
        if (records.isEmpty()) {
            return true;
        }
        List<List<SourceRecord>> groups = SourceRecordGrouper.groupByCorrelationId(records);
        IntakeResult result = slackLane.run(groups.stream(), Instant.now());
        if (result.rejected() > 0) {
            log.warn(
                    "Slack live intake rejected {} of {} group(s), Slack will be asked to retry: {}",
                    result.rejected(), result.read(), result.errors());
            return false;
        }
        return true;
    }

    /**
     * Non-thread real-time path: lands each newly-arrived attachment to MinIO immediately and
     * records its ref on GroupState (see GroupStateStore.recordLandedAttachment) - never
     * publishes to Kafka here. scopeDecision is computed per attachment purely for MinIO object
     * tagging (ObjectTags), same as before this migration; the event eventually published at
     * session close carries only the transcript's own scope decision (see
     * UnstructuredPayloadMapper's javadoc for why there is no per-object scope field anymore).
     */
    private boolean landAttachments(List<SourceRecord> records) {
        boolean allLanded = true;
        Instant now = Instant.now();
        for (SourceRecord record : records) {
            try {
                ScopeDecision scopeDecision = scopingFilter.decide(record);
                RawObjectRef ref = refMapper.toRef(record, now);
                landingService.land(ref.objectKey(), record, scopeDecision, now);
                String messageId = record.sourceMessageIds().get(0);
                groupStateStore.recordLandedAttachment(
                        record.sourceType(), record.tenantId(), record.groupId(), messageId,
                        record.attachmentFileId(), ref);
            } catch (RuntimeException e) {
                log.warn("Failed to land Slack attachment for naturalId={}", record.naturalId(), e);
                allLanded = false;
            }
        }
        return allLanded;
    }

    private static String readRawBody(HttpServletRequest request) throws IOException {
        return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
