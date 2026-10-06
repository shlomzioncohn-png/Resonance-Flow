package com.rnd.bridge.intake.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.intake.config.SlackApiProperties;
import com.rnd.bridge.intake.lane.UnstructuredIntakeLane;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.parser.slack.SlackUserNameResolver;
import com.rnd.bridge.intake.service.ChatGroupStateReconciler;
import com.rnd.bridge.intake.service.SlackSessionScheduler;
import com.rnd.bridge.raw.model.RawSourceType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Direct unit tests (no MockMvc/Spring context, matching this module's existing controller test
 * style) - the controller and its raw-body reading are exercised together since the whole point
 * of this endpoint is that signature verification runs over the exact bytes Slack signed.
 * eventMessageParser/reconciler/sessionScheduler/the Slack lane are all mocked here -
 * JSON-to-ChatMessage parsing is covered by SlackEventMessageParserTest, reconcile-only
 * merge/persist semantics by ChatGroupStateReconcilerTest, and scheduler internals by
 * SlackSessionSchedulerTest; this class only verifies the controller wires them together
 * correctly (threaded vs. non-thread routing, transcript withheld, scheduling based on whether
 * the resulting group still needs publication - not on whether the event was a genuinely-new
 * message or an edit, unsupported events acked without touching the reconciler).
 */
class SlackEventsControllerTest {

    private static final String TIMESTAMP_HEADER = "X-Slack-Request-Timestamp";
    private static final String SIGNATURE_HEADER = "X-Slack-Signature";

    private final SlackSignatureVerifier verifier = mock(SlackSignatureVerifier.class);
    private final SlackEventMessageParser eventMessageParser = mock(SlackEventMessageParser.class);
    private final ChatGroupStateReconciler reconciler = mock(ChatGroupStateReconciler.class);
    private final SlackSessionScheduler sessionScheduler = mock(SlackSessionScheduler.class);
    private final UnstructuredIntakeLane slackLane = mockSlackLane();
    private final SlackEventsController controller = new SlackEventsController(
            verifier, new ObjectMapper(), eventMessageParser, reconciler, sessionScheduler, List.of(slackLane));

    // Real (unmocked) parser, used only by the message_changed end-to-end tests below - these
    // exist specifically to prove realistic Slack JSON produces a ChatMessage the reconciler can
    // actually match by messageId, which SlackEventMessageParserTest alone doesn't demonstrate
    // (it never goes through this controller's routing/scheduling).
    private final SlackUserNameResolver realParserUserNameResolver = mockPassThroughResolver();
    private final SlackEventMessageParser realEventMessageParser = new SlackEventMessageParser(
            new SlackApiProperties("xoxb-fake", "kamatech", List.of("chat-a"), "shh"), realParserUserNameResolver);

    private static SlackUserNameResolver mockPassThroughResolver() {
        SlackUserNameResolver resolver = mock(SlackUserNameResolver.class);
        when(resolver.resolveUserName(any())).thenAnswer(invocation -> {
            String userId = invocation.getArgument(0);
            return userId == null ? "unknown" : userId;
        });
        return resolver;
    }
    private final SlackEventsController controllerWithRealParser = new SlackEventsController(
            verifier, new ObjectMapper(), realEventMessageParser, reconciler, sessionScheduler, List.of(slackLane));

    private static UnstructuredIntakeLane mockSlackLane() {
        UnstructuredIntakeLane lane = mock(UnstructuredIntakeLane.class);
        when(lane.sourceType()).thenReturn(RawSourceType.SLACK);
        return lane;
    }

    private ChatMessage nonThreadMessage() {
        return new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "U1", Instant.parse("2026-08-18T09:00:00Z"),
                "hi", List.of(), "1000.1", null);
    }

    private ChatMessage threadMessage() {
        return new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "U1", Instant.parse("2026-08-18T09:00:00Z"),
                "reply", List.of(), "1000.2", "1000.0");
    }

    private GroupState groupState() {
        return groupState(false);
    }

    private GroupState groupState(boolean published) {
        return new GroupState(
                "g1", RawSourceType.SLACK, "kamatech", "chat-a", null, "corr-1",
                List.of(new MessageSnapshot("1000.1", "U1", Instant.parse("2026-08-18T09:00:00Z"), "hi", 0)),
                published, 0, null);
    }

    private SourceRecord attachmentRecord() {
        return new SourceRecord(
                RawSourceType.SLACK, "kamatech", "chat-a|1000.1|attachment-1", "corr-1",
                Instant.parse("2026-08-18T09:00:00Z"), "U1", List.of("U1"), null, null,
                "bytes".getBytes(StandardCharsets.UTF_8), "text/plain", "note.txt", 1, 1, List.of("1000.1"), 0);
    }

    @Test
    void correctlySignedUrlVerificationReturnsChallenge() throws Exception {
        String body = "{\"type\":\"url_verification\",\"challenge\":\"abc123\"}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new SlackChallengeResponse("abc123"));
    }

    @Test
    void invalidlySignedChallengeIsRejected() throws Exception {
        String body = "{\"type\":\"url_verification\",\"challenge\":\"abc123\"}";
        when(verifier.isValid("1000", "v0=bad", body)).thenReturn(false);

        ResponseEntity<?> response = controller.handle("1000", "v0=bad", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void missingSignatureHeaderIsRejected() throws Exception {
        String body = "{\"type\":\"event_callback\"}";
        when(verifier.isValid(null, null, body)).thenReturn(false);

        ResponseEntity<?> response = controller.handle(null, null, requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        String body = "{not valid json";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unsupportedEnvelopeTypeIsRejected() throws Exception {
        String body = "{\"type\":\"app_rate_limited\"}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingTypeFieldIsRejected() throws Exception {
        String body = "{\"foo\":\"bar\"}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unsupportedEventCallbackIsAcknowledgedWithoutTouchingTheReconciler() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"subtype\":\"message_changed\"}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        when(eventMessageParser.parse(any())).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNull();
        verify(reconciler, never()).group(any());
        verify(reconciler, never()).reconcileNonThreadedMessage(any());
        verify(slackLane, never()).run(any(Stream.class), any());
        verify(sessionScheduler, never()).scheduleOrReschedule(any());
    }

    @Test
    void nonThreadMessageWithoutAttachmentIsReconciledAndSchedulesTheTimerButPublishesNothing() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\"}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        ChatMessage message = nonThreadMessage();
        GroupState groupState = groupState();
        when(eventMessageParser.parse(any())).thenReturn(Optional.of(message));
        when(reconciler.reconcileNonThreadedMessage(message))
                .thenReturn(new ChatGroupStateReconciler.RealtimeReconcileResult(groupState, List.of(), true));

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(reconciler).reconcileNonThreadedMessage(message);
        verify(reconciler, never()).group(any());
        verify(slackLane, never()).run(any(Stream.class), any());
        verify(sessionScheduler).scheduleOrReschedule(groupState);
    }

    @Test
    void nonThreadMessageWithAttachmentPublishesOnlyTheAttachmentRecordAndSchedulesTheTimer() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\"}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        ChatMessage message = nonThreadMessage();
        SourceRecord attachment = attachmentRecord();
        GroupState groupState = groupState();
        when(eventMessageParser.parse(any())).thenReturn(Optional.of(message));
        when(reconciler.reconcileNonThreadedMessage(message))
                .thenReturn(new ChatGroupStateReconciler.RealtimeReconcileResult(groupState, List.of(attachment), true));
        when(slackLane.run(any(Stream.class), any())).thenReturn(new IntakeResult(1, 1, 0, List.of()));

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(reconciler).reconcileNonThreadedMessage(message);
        verify(reconciler, never()).group(any());
        verify(slackLane).run(any(Stream.class), any());
        verify(sessionScheduler).scheduleOrReschedule(groupState);
    }

    @Test
    void nonThreadMessageWhoseAttachmentPublishIsRejectedReturnsANon2xxSoSlackRetries() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\"}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        ChatMessage message = nonThreadMessage();
        SourceRecord attachment = attachmentRecord();
        GroupState groupState = groupState();
        when(eventMessageParser.parse(any())).thenReturn(Optional.of(message));
        when(reconciler.reconcileNonThreadedMessage(message))
                .thenReturn(new ChatGroupStateReconciler.RealtimeReconcileResult(groupState, List.of(attachment), true));
        // A rejected record (e.g. a MinIO landing/Kafka publish failure) must not be silently
        // acknowledged - see IntakeResult's rejected count and errors.
        when(slackLane.run(any(Stream.class), any()))
                .thenReturn(new IntakeResult(1, 0, 1, List.of(new com.rnd.bridge.core.error.LaneException("boom"))));

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(slackLane).run(any(Stream.class), any());
        // The GroupState merge/persist already happened and stands regardless of the publish
        // outcome - only the ACK reflects the failure, nothing about reconciliation is undone.
        verify(sessionScheduler).scheduleOrReschedule(groupState);
    }

    @Test
    void editOfAnAlreadyIndexedMessageThatIsStillUnpublishedStillSchedules() throws Exception {
        // An edit can supersede an OPEN, CLAIMED, or even already-PUBLISHED group with a fresh
        // unpublished revision (see ChatGroupStateReconciler.mergeIntoGroupState) - that revision
        // still needs a live timer, so scheduling must not skip edits anymore.
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\"}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        ChatMessage message = nonThreadMessage();
        GroupState groupState = groupState(false);
        when(eventMessageParser.parse(any())).thenReturn(Optional.of(message));
        when(reconciler.reconcileNonThreadedMessage(message))
                .thenReturn(new ChatGroupStateReconciler.RealtimeReconcileResult(groupState, List.of(), false));

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(sessionScheduler).scheduleOrReschedule(groupState);
    }

    @Test
    void reconcileResultThatIsAlreadyPublishedIsNeverScheduled() throws Exception {
        // Scheduling is gated on groupState().published(), not on new-vs-edit - an already
        // published, unchanged result (e.g. a genuine no-op re-read of a confirmed group) must
        // never install a pointless timer.
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\"}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        ChatMessage message = nonThreadMessage();
        GroupState groupState = groupState(true);
        when(eventMessageParser.parse(any())).thenReturn(Optional.of(message));
        when(reconciler.reconcileNonThreadedMessage(message))
                .thenReturn(new ChatGroupStateReconciler.RealtimeReconcileResult(groupState, List.of(), false));

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(sessionScheduler, never()).scheduleOrReschedule(any());
    }

    @Test
    void threadMessageStillGoesThroughTheExistingImmediatePublishPathAndNeverSchedules() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"thread_ts\":\"1000.0\"}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        ChatMessage message = threadMessage();
        SourceRecord transcript = attachmentRecord(); // stand-in payload, only routing is under test here
        when(eventMessageParser.parse(any())).thenReturn(Optional.of(message));
        when(reconciler.group(List.of(message))).thenReturn(List.of(transcript));
        when(slackLane.run(any(Stream.class), any())).thenReturn(new IntakeResult(1, 1, 0, List.of()));

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(reconciler).group(List.of(message));
        verify(reconciler, never()).reconcileNonThreadedMessage(any());
        verify(slackLane).run(any(Stream.class), any());
        verify(sessionScheduler, never()).scheduleOrReschedule(any());
    }

    @Test
    void threadMessageWhosePublishIsRejectedReturnsANon2xxSoSlackRetries() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"thread_ts\":\"1000.0\"}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        ChatMessage message = threadMessage();
        SourceRecord transcript = attachmentRecord();
        when(eventMessageParser.parse(any())).thenReturn(Optional.of(message));
        when(reconciler.group(List.of(message))).thenReturn(List.of(transcript));
        when(slackLane.run(any(Stream.class), any()))
                .thenReturn(new IntakeResult(1, 0, 1, List.of(new com.rnd.bridge.core.error.LaneException("boom"))));

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(slackLane).run(any(Stream.class), any());
    }

    @Test
    void nonThreadMessageChangedEndToEndUsesTheOriginalMessagesStableIdToReconcile() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"subtype\":\"message_changed\","
                + "\"channel\":\"chat-a\",\"ts\":\"1000.9\","
                + "\"message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.1\",\"text\":\"edited\"},"
                + "\"previous_message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.1\",\"text\":\"hi\"}}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        GroupState groupState = groupState(false);
        when(reconciler.reconcileNonThreadedMessage(any()))
                .thenReturn(new ChatGroupStateReconciler.RealtimeReconcileResult(groupState, List.of(), false));

        ResponseEntity<?> response = controllerWithRealParser.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(reconciler).reconcileNonThreadedMessage(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo("1000.1");
        assertThat(captor.getValue().text()).isEqualTo("edited");
        assertThat(captor.getValue().threadId()).isNull();
        verify(reconciler, never()).group(any());
        verify(sessionScheduler).scheduleOrReschedule(groupState);
    }

    @Test
    void threadReplyMessageChangedEndToEndGoesThroughTheImmediatePublishPathWithTheOriginalReplyId() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"subtype\":\"message_changed\","
                + "\"channel\":\"chat-a\",\"ts\":\"1000.9\","
                + "\"message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.2\",\"thread_ts\":\"1000.0\","
                + "\"text\":\"edited reply\"},"
                + "\"previous_message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.2\",\"thread_ts\":\"1000.0\","
                + "\"text\":\"reply\"}}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        SourceRecord transcript = attachmentRecord();
        when(reconciler.group(any())).thenReturn(List.of(transcript));
        when(slackLane.run(any(Stream.class), any())).thenReturn(new IntakeResult(1, 1, 0, List.of()));

        ResponseEntity<?> response = controllerWithRealParser.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciler).group(captor.capture());
        ChatMessage message = captor.getValue().get(0);
        assertThat(message.id()).isEqualTo("1000.2");
        assertThat(message.threadId()).isEqualTo("1000.0");
        assertThat(message.text()).isEqualTo("edited reply");
        verify(reconciler, never()).reconcileNonThreadedMessage(any());
        verify(slackLane).run(any(Stream.class), any());
        verify(sessionScheduler, never()).scheduleOrReschedule(any());
    }

    @Test
    void messageDeletedEndToEndIsAcknowledgedWithoutTouchingTheReconciler() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"subtype\":\"message_deleted\","
                + "\"channel\":\"chat-a\",\"ts\":\"1000.9\",\"deleted_ts\":\"1000.1\","
                + "\"previous_message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.1\",\"text\":\"hi\"}}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);

        ResponseEntity<?> response = controllerWithRealParser.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(reconciler, never()).group(any());
        verify(reconciler, never()).reconcileNonThreadedMessage(any());
        verify(slackLane, never()).run(any(Stream.class), any());
    }

    private static MockHttpServletRequest requestWithBody(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
