package com.rnd.bridge.intake.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.intake.config.SlackApiProperties;
import com.rnd.bridge.intake.lane.GroupStateStore;
import com.rnd.bridge.intake.lane.UnstructuredIntakeLane;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.parser.slack.SlackUserNameResolver;
import com.rnd.bridge.intake.service.ChatGroupStateReconciler;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.intake.service.SlackSessionScheduler;
import com.rnd.bridge.intake.service.SourceRecordRefMapper;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.scoping.AllowlistScopingFilter;
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
 * correctly: threaded messages go through the lane as one group-per-event, non-thread messages
 * only land attachments (never publish to Kafka here - see SlackSessionPublisher for that),
 * scheduling is based on whether the resulting group still needs publication, and unsupported
 * events are acked without touching the reconciler.
 */
class SlackEventsControllerTest {

    private static final String TIMESTAMP_HEADER = "X-Slack-Request-Timestamp";
    private static final String SIGNATURE_HEADER = "X-Slack-Signature";

    private final SlackSignatureVerifier verifier = mock(SlackSignatureVerifier.class);
    private final SlackEventMessageParser eventMessageParser = mock(SlackEventMessageParser.class);
    private final ChatGroupStateReconciler reconciler = mock(ChatGroupStateReconciler.class);
    private final SlackSessionScheduler sessionScheduler = mock(SlackSessionScheduler.class);
    private final UnstructuredIntakeLane slackLane = mockSlackLane();
    private final AllowlistScopingFilter scopingFilter = mock(AllowlistScopingFilter.class);
    private final SourceRecordRefMapper refMapper = mock(SourceRecordRefMapper.class);
    private final IntakeLandingService landingService = mock(IntakeLandingService.class);
    private final GroupStateStore groupStateStore = mock(GroupStateStore.class);
    private final SlackEventsController controller = new SlackEventsController(
            verifier, new ObjectMapper(), eventMessageParser, reconciler, sessionScheduler, List.of(slackLane),
            scopingFilter, refMapper, landingService, groupStateStore);

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
            verifier, new ObjectMapper(), realEventMessageParser, reconciler, sessionScheduler, List.of(slackLane),
            scopingFilter, refMapper, landingService, groupStateStore);

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
                "bytes".getBytes(StandardCharsets.UTF_8), "text/plain", "note.txt", "F1", 1, 1, List.of("1000.1"), 0,
                "g1");
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
    void nonThreadMessageWithoutAttachmentIsReconciledAndSchedulesTheTimerButLandsNothing() throws Exception {
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
        verifyNoInteractions(landingService, groupStateStore);
        verify(sessionScheduler).scheduleOrReschedule(groupState);
    }

    @Test
    void nonThreadMessageWithAttachmentLandsItAndRecordsItOnGroupStateWithoutPublishingToKafka() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\"}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        ChatMessage message = nonThreadMessage();
        SourceRecord attachment = attachmentRecord();
        GroupState groupState = groupState();
        RawObjectRef ref = new RawObjectRef(
                Role.ATTACHMENT, "SLACK/kamatech/2026-08-18/x.txt", "sha256:" + "a".repeat(64), "text/plain", 5L,
                Modality.TEXT);
        when(eventMessageParser.parse(any())).thenReturn(Optional.of(message));
        when(reconciler.reconcileNonThreadedMessage(message))
                .thenReturn(new ChatGroupStateReconciler.RealtimeReconcileResult(groupState, List.of(attachment), true));
        when(scopingFilter.decide(attachment)).thenReturn(ScopeDecision.INCLUDED);
        when(refMapper.toRef(eq(attachment), any())).thenReturn(ref);

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(reconciler).reconcileNonThreadedMessage(message);
        verify(reconciler, never()).group(any());
        verify(slackLane, never()).run(any(Stream.class), any());
        verify(landingService).land(eq(ref.objectKey()), eq(attachment), eq(ScopeDecision.INCLUDED), any());
        verify(groupStateStore).recordLandedAttachment(
                RawSourceType.SLACK, "kamatech", "g1", "1000.1", "F1", ref);
        verify(sessionScheduler).scheduleOrReschedule(groupState);
    }

    @Test
    void nonThreadMessageWhoseAttachmentLandingFailsReturnsANon2xxSoSlackRetries() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\"}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        ChatMessage message = nonThreadMessage();
        SourceRecord attachment = attachmentRecord();
        GroupState groupState = groupState();
        when(eventMessageParser.parse(any())).thenReturn(Optional.of(message));
        when(reconciler.reconcileNonThreadedMessage(message))
                .thenReturn(new ChatGroupStateReconciler.RealtimeReconcileResult(groupState, List.of(attachment), true));
        when(scopingFilter.decide(attachment)).thenReturn(ScopeDecision.INCLUDED);
        doThrow(new RuntimeException("MinIO unreachable")).when(landingService).land(any(), any(), any(), any());

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        // The GroupState merge/persist already happened and stands regardless of the landing
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
    void nonThreadEventThatResolvesToAnExistingThreadGroupPublishesImmediatelyInsteadOfScheduling() throws Exception {
        // A thread root's edit: the event itself carries no threadId (roots never do), but
        // reconciler.reconcileNonThreadedMessage resolved it to an existing thread group (see
        // ChatGroupStateReconciler.resolveCurrentGroupId) - threadRouted=true means the full
        // record set (transcript included) must be published immediately via the lane, exactly
        // like the ordinary threaded path, never landed/scheduled.
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\"}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        ChatMessage message = nonThreadMessage();
        SourceRecord transcript = attachmentRecord();
        when(eventMessageParser.parse(any())).thenReturn(Optional.of(message));
        when(reconciler.reconcileNonThreadedMessage(message)).thenReturn(
                new ChatGroupStateReconciler.RealtimeReconcileResult(groupState(), List.of(transcript), false, true, null));
        when(slackLane.run(any(Stream.class), any())).thenReturn(new IntakeResult(1, 1, 0, List.of()));

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(slackLane).run(any(Stream.class), any());
        verifyNoInteractions(landingService, groupStateStore);
        verify(sessionScheduler, never()).scheduleOrReschedule(any());
    }

    /**
     * docs/plans/slack-superseded-fix.md §2b's ordering contract: when a root edit also updated
     * the channel group's own copy of the root, that channel-group scheduling must happen
     * regardless of whether the thread publish (the only step whose result participates in the
     * returned boolean/HTTP status) succeeds. Here the thread publish fails (IntakeResult with
     * rejected() > 0) - the overall response must still be non-2xx (Slack retries), but the
     * channel group must still have been scheduled, not skipped or rolled back.
     */
    @Test
    void channelGroupSchedulingStillHappensWhenTheThreadPublishFails() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\"}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        ChatMessage message = nonThreadMessage();
        SourceRecord transcript = attachmentRecord();
        GroupState channelGroupState = groupState();
        when(eventMessageParser.parse(any())).thenReturn(Optional.of(message));
        when(reconciler.reconcileNonThreadedMessage(message)).thenReturn(
                new ChatGroupStateReconciler.RealtimeReconcileResult(
                        groupState(), List.of(transcript), false, true, channelGroupState));
        when(slackLane.run(any(Stream.class), any())).thenReturn(new IntakeResult(1, 0, 1, List.of()));

        ResponseEntity<?> response = controller.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(sessionScheduler).scheduleOrReschedule(channelGroupState);
        verify(slackLane).run(any(Stream.class), any());
    }

    @Test
    void messageDeletedOfAnUnknownMessageIsAckedAsANoOpWithoutPublishing() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"subtype\":\"message_deleted\","
                + "\"channel\":\"chat-a\",\"ts\":\"1000.9\",\"deleted_ts\":\"1000.1\","
                + "\"previous_message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.1\",\"text\":\"hi\"}}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        when(reconciler.removeMessage(RawSourceType.SLACK, "kamatech", "1000.1"))
                .thenReturn(new ChatGroupStateReconciler.RemovalResult(null, false, false, null));

        ResponseEntity<?> response = controllerWithRealParser.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(reconciler, never()).group(any());
        verify(reconciler, never()).reconcileNonThreadedMessage(any());
        verify(slackLane, never()).run(any(Stream.class), any());
        verify(sessionScheduler, never()).scheduleOrReschedule(any());
    }

    @Test
    void messageDeletedOfAMessageInANonThreadGroupSchedulesButNeverPublishesDirectly() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"subtype\":\"message_deleted\","
                + "\"channel\":\"chat-a\",\"ts\":\"1000.9\",\"deleted_ts\":\"1000.1\","
                + "\"previous_message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.1\",\"text\":\"hi\"}}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        GroupState groupState = groupState();
        when(reconciler.removeMessage(RawSourceType.SLACK, "kamatech", "1000.1"))
                .thenReturn(new ChatGroupStateReconciler.RemovalResult(groupState, true, false, null));

        ResponseEntity<?> response = controllerWithRealParser.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(slackLane, never()).run(any(Stream.class), any());
        verify(sessionScheduler).scheduleOrReschedule(groupState);
    }

    @Test
    void messageDeletedResolvingToAThreadGroupPublishesImmediately() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"subtype\":\"message_deleted\","
                + "\"channel\":\"chat-a\",\"ts\":\"1000.9\",\"deleted_ts\":\"1000.2\","
                + "\"previous_message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.2\",\"text\":\"reply\"}}}";
        when(verifier.isValid("1000", "v0=valid", body)).thenReturn(true);
        GroupState threadGroupState = new GroupState(
                "1000.0", RawSourceType.SLACK, "kamatech", "chat-a", "1000.0", "corr-1", List.of(), false, 1, null);
        SourceRecord deleteReview = attachmentRecord();
        when(reconciler.removeMessage(RawSourceType.SLACK, "kamatech", "1000.2"))
                .thenReturn(new ChatGroupStateReconciler.RemovalResult(threadGroupState, true, true, null));
        when(reconciler.toSourceRecordsForPublishedGroup(threadGroupState)).thenReturn(List.of(deleteReview));
        when(slackLane.run(any(Stream.class), any())).thenReturn(new IntakeResult(1, 1, 0, List.of()));

        ResponseEntity<?> response = controllerWithRealParser.handle("1000", "v0=valid", requestWithBody(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(slackLane).run(any(Stream.class), any());
        verify(sessionScheduler, never()).scheduleOrReschedule(any());
    }

    private static MockHttpServletRequest requestWithBody(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
