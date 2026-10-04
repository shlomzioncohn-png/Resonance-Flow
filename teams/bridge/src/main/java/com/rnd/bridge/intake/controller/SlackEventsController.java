package com.rnd.bridge.intake.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.intake.lane.UnstructuredIntakeLane;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.ChatGroupStateReconciler;
import com.rnd.bridge.intake.service.SlackSessionScheduler;
import com.rnd.bridge.raw.model.RawSourceType;
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
 *   threads (the delayed-transcript design is specific to non-thread time-gap sessions).
 * - non-thread messages go through {@link ChatGroupStateReconciler#reconcileNonThreadedMessage},
 *   which merges+persists the group but withholds the transcript record; only this message's own
 *   newly-arrived attachment records (if any) are published, via the same Slack
 *   {@link UnstructuredIntakeLane} the batch/poll path already publishes through (map -> MinIO
 *   landing -> validate -> Kafka publish -> afterPublish) - see
 *   AbstractIntakeLane.run(Stream, Instant), the overload built for exactly this kind of caller.
 * An unsupported/unparseable event shape is still acknowledged with 200 (Slack must not retry
 * it), but never reaches the reconciler - GroupState is left untouched. A supported event that
 * WAS reconciled but whose lane run rejected any record (validation/MinIO/Kafka failure - see
 * {@link #publish}) gets a non-2xx instead, so Slack retries it rather than believing a failed
 * publish succeeded.
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

    public SlackEventsController(
            SlackSignatureVerifier signatureVerifier, ObjectMapper objectMapper,
            SlackEventMessageParser eventMessageParser, ChatGroupStateReconciler reconciler,
            SlackSessionScheduler sessionScheduler, List<UnstructuredIntakeLane> lanes) {
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
        this.eventMessageParser = eventMessageParser;
        this.reconciler = reconciler;
        this.sessionScheduler = sessionScheduler;
        this.slackLane = lanes.stream()
                .filter(lane -> lane.sourceType() == RawSourceType.SLACK)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No SLACK UnstructuredIntakeLane configured"));
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
     * A verified event_callback is acknowledged with 200 only once every record it produced
     * actually landed/validated/published successfully - see {@link #publish}. An unsupported/
     * unparseable event shape is still acknowledged with 200 (nothing was attempted, so there is
     * nothing to retry); a supported event whose lane run rejected anything gets a non-2xx so
     * Slack retries it. This is safe to retry synchronously: reconciler.reconcileNonThreadedMessage
     * /reconciler.group() already durably persisted the merged GroupState (see
     * ChatGroupStateReconciler.mergeIntoGroupState) before this method is even reached, and a
     * redelivery re-resolves the same messageId to the same group rather than creating a
     * duplicate one.
     */
    private ResponseEntity<?> handleEventCallback(JsonNode root) {
        Optional<ChatMessage> parsed = eventMessageParser.parse(root.get("event"));
        if (parsed.isEmpty()) {
            return ResponseEntity.ok().build();
        }
        boolean allPublished = reconcileAndPublish(parsed.get());
        return allPublished
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    private boolean reconcileAndPublish(ChatMessage message) {
        if (message.threadId() != null) {
            // Threaded groups keep today's immediate merge+publish semantics - unaffected by the
            // non-thread delayed-transcript design (see class javadoc), and never scheduled.
            return publish(reconciler.group(List.of(message)));
        }
        // Only newly-arrived attachment records come back here, never the transcript - the
        // transcript stays pending in GroupState for SlackSessionScheduler's inactivity timer.
        ChatGroupStateReconciler.RealtimeReconcileResult result = reconciler.reconcileNonThreadedMessage(message);
        boolean allPublished = publish(result.newAttachmentRecords());
        // Scheduling is keyed on "does this GroupState still need publishing", not on whether
        // this event was a genuinely-new message - an edit can just as well produce a fresh
        // unpublished revision (superseding an OPEN, CLAIMED, or even already-PUBLISHED group -
        // see ChatGroupStateReconciler.mergeIntoGroupState), and that revision must still get a
        // live timer or it would never reach Kafka. scheduleOrReschedule always derives its
        // deadline from GroupState's own message timestamps (see its javadoc), never from when
        // this event arrived, so an edit never artificially extends the session gap - if the
        // computed deadline is already in the past (e.g. editing a long-since-published
        // session), the TaskScheduler simply runs the callback promptly instead of waiting.
        if (!result.groupState().published()) {
            sessionScheduler.scheduleOrReschedule(result.groupState());
        }
        return allPublished;
    }

    // Same map -> land -> validate -> publish -> afterPublish loop the batch/poll path runs,
    // just against an already-built Stream instead of one read via SourceReader - see
    // AbstractIntakeLane.run(Stream, Instant)'s javadoc. Unlike the batch path (which just
    // freezes/advances a cursor around this same IntakeResult), this real-time caller has no
    // cursor to freeze - the only way to make a rejection retryable is to tell Slack to redeliver
    // the event, so a non-empty IntakeResult.errors() is logged and turned into a false return
    // instead of being silently discarded.
    private boolean publish(List<SourceRecord> records) {
        if (records.isEmpty()) {
            return true;
        }
        IntakeResult result = slackLane.run(records.stream(), Instant.now());
        if (result.rejected() > 0) {
            log.warn(
                    "Slack live intake rejected {} of {} record(s), Slack will be asked to retry: {}",
                    result.rejected(), result.read(), result.errors());
            return false;
        }
        return true;
    }

    private static String readRawBody(HttpServletRequest request) throws IOException {
        return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
