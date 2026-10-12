package com.rnd.bridge.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.media.classification.DocumentClassificationInput;
import com.rnd.bridge.media.classification.MediaClassificationService;
import com.rnd.bridge.media.classification.Triage;
import com.rnd.bridge.media.meeting.FixtureBbbMeetingMetadataReader;
import com.rnd.bridge.media.meeting.FixtureZoomMeetingMetadataReader;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Classification;
import com.rnd.bridge.raw.model.ClassifierInfo;
import com.rnd.bridge.raw.model.DecidedBy;
import com.rnd.bridge.raw.model.DocumentCategory;
import com.rnd.bridge.raw.model.Label;
import com.rnd.bridge.raw.model.Quality;
import com.rnd.bridge.raw.model.Sensitivity;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class MediaIntakeOrchestratorTest {

    @TempDir
    Path zoomDir;
    @TempDir
    Path bbbDir;
    @TempDir
    Path scannedDocsDir;

    private final MediaIntakeService intakeService = mock(MediaIntakeService.class);
    private final MediaEventPublisher eventPublisher = mock(MediaEventPublisher.class);
    private final MediaDedupIndex dedupIndex = mock(MediaDedupIndex.class);
    private final MediaClassificationService classificationService = mock(MediaClassificationService.class);

    private MediaIntakeOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        when(dedupIndex.checkAndRecord(any(), any(), any())).thenReturn(false);
        when(classificationService.triageMeeting(any(), any())).thenReturn(Triage.UNCLASSIFIED);
        when(classificationService.triageDocument(any())).thenReturn(Triage.UNCLASSIFIED);
        orchestrator = new MediaIntakeOrchestrator(
                intakeService, eventPublisher, dedupIndex, classificationService,
                new FixtureZoomMeetingMetadataReader(new ObjectMapper()),
                new FixtureBbbMeetingMetadataReader(),
                zoomDir.toString(), bbbDir.toString(), scannedDocsDir.toString(),
                "demo-tenant", 1024L);
    }

    @Test
    void emptyFileIsRejectedNotLanded() throws Exception {
        Files.write(zoomDir.resolve("empty.mp3"), new byte[0]);

        MediaIntakeOrchestrator.IntakeSummary summary = orchestrator.runIntake();

        assertThat(summary.rejectedFiles()).containsExactly("empty.mp3 (empty)");
        assertThat(Files.exists(zoomDir.resolve("rejected").resolve("empty.mp3"))).isTrue();
        verifyNoInteractions(intakeService, eventPublisher);
    }

    @Test
    void fileBelowMinSizeIsRejected() throws Exception {
        Files.write(zoomDir.resolve("tiny.mp3"), new byte[10]);

        MediaIntakeOrchestrator.IntakeSummary summary = orchestrator.runIntake();

        assertThat(summary.rejectedFiles()).containsExactly("tiny.mp3 (below-min-size)");
        assertThat(Files.exists(zoomDir.resolve("rejected").resolve("tiny.mp3"))).isTrue();
        verifyNoInteractions(intakeService, eventPublisher);
    }

    @Test
    void validFileIsLandedPublishedAndMovedToUploaded() throws Exception {
        Files.write(zoomDir.resolve("meeting.mp3"), "a".repeat(2000).getBytes());
        RawObjectEvent event = fakeMeetingEvent("ZOOM/demo-tenant/2026-08-30/x.mp3");
        when(intakeService.landMeeting(any(), any(), eq("meeting.mp3"), isNull(), isNull(), isNull(),
                isNull(), eq("demo-tenant"), eq(RawSourceType.ZOOM), any(), isNull(), isNull(),
                isNull(), any(), eq(Triage.UNCLASSIFIED)))
                .thenReturn(event);

        MediaIntakeOrchestrator.IntakeSummary summary = orchestrator.runIntake();

        assertThat(summary.uploadedObjectKeys()).containsExactly(event.objects().get(0).objectKey());
        assertThat(Files.exists(zoomDir.resolve("uploaded").resolve("meeting.mp3"))).isTrue();
        verify(eventPublisher).publish(event);
        verify(dedupIndex).checkAndRecord(eq(RawSourceType.ZOOM), eq("demo-tenant"), any());
    }

    @Test
    void bigBlueButtonRecordingLandsAsItsOwnSourceType() throws Exception {
        Files.write(bbbDir.resolve("meeting.mp4"), "h".repeat(2000).getBytes());
        RawObjectEvent event = fakeMeetingEvent("BIGBLUEBUTTON/demo-tenant/2026-08-30/x.mp4");
        when(intakeService.landMeeting(any(), any(), eq("meeting.mp4"), isNull(), isNull(), isNull(),
                isNull(), eq("demo-tenant"), eq(RawSourceType.BIGBLUEBUTTON), any(), isNull(), isNull(),
                isNull(), any(), eq(Triage.UNCLASSIFIED)))
                .thenReturn(event);

        orchestrator.runIntake();

        verify(eventPublisher).publish(event);
        verify(dedupIndex).checkAndRecord(eq(RawSourceType.BIGBLUEBUTTON), eq("demo-tenant"), any());
    }

    @Test
    void duplicateContentIsSkippedButStillMovedToUploaded() throws Exception {
        byte[] content = "b".repeat(2000).getBytes();
        Files.write(zoomDir.resolve("dup.mp3"), content);
        when(dedupIndex.checkAndRecord(eq(RawSourceType.ZOOM), eq("demo-tenant"), eq(sha256(content))))
                .thenReturn(true);

        MediaIntakeOrchestrator.IntakeSummary summary = orchestrator.runIntake();

        assertThat(summary.duplicateFiles()).containsExactly("dup.mp3");
        assertThat(Files.exists(zoomDir.resolve("uploaded").resolve("dup.mp3"))).isTrue();
        verifyNoInteractions(intakeService, eventPublisher);
    }

    @Test
    void transcriptSidecarIsLandedAndLinkedNotProcessedOnItsOwn() throws Exception {
        Files.write(zoomDir.resolve("meeting.mp3"), "c".repeat(2000).getBytes());
        Files.writeString(zoomDir.resolve("meeting.vtt"), "WEBVTT transcript");
        RawObjectEvent event = fakeMeetingEvent("ZOOM/demo-tenant/2026-08-30/x.mp3");
        when(intakeService.landMeeting(any(), any(), eq("meeting.mp3"), any(), eq("meeting.vtt"), isNull(),
                isNull(), eq("demo-tenant"), eq(RawSourceType.ZOOM), any(), isNull(), isNull(),
                isNull(), any(), eq(Triage.UNCLASSIFIED)))
                .thenReturn(event);

        MediaIntakeOrchestrator.IntakeSummary summary = orchestrator.runIntake();

        assertThat(summary.uploadedObjectKeys()).containsExactly(event.objects().get(0).objectKey());
        assertThat(summary.failedFiles()).isEmpty();
        assertThat(Files.exists(zoomDir.resolve("uploaded").resolve("meeting.vtt"))).isTrue();
        verify(intakeService).landMeeting(any(), any(), eq("meeting.mp3"), any(), eq("meeting.vtt"), isNull(),
                isNull(), eq("demo-tenant"), eq(RawSourceType.ZOOM), any(), isNull(), isNull(),
                isNull(), any(), eq(Triage.UNCLASSIFIED));
    }

    @Test
    void meetingMetadataSidecarIsLandedLinkedParticipantsAndSourceRefExtracted() throws Exception {
        Files.write(zoomDir.resolve("meeting.mp3"), "e".repeat(2000).getBytes());
        Files.writeString(zoomDir.resolve("meeting.meeting.json"), """
                {"id": "1", "topic": "Sync", "user_email": "dana@example.com",
                 "start_time": "2026-09-08T09:00:00Z", "end_time": "2026-09-08T09:30:00Z",
                 "participants": [
                   {"id": "a", "name": "Dana Levi", "join_time": "2026-09-08T09:00:00Z", "leave_time": "2026-09-08T09:30:00Z"},
                   {"id": "b", "name": "Esther Ora", "join_time": "2026-09-08T09:01:00Z", "leave_time": "2026-09-08T09:30:00Z"}
                 ]}""");
        Instant meetingStartedAt = Instant.parse("2026-09-08T09:00:00Z");
        RawObjectEvent event = fakeMeetingEvent("ZOOM/demo-tenant/2026-08-30/x.mp3");
        when(intakeService.landMeeting(any(), any(), eq("meeting.mp3"), isNull(), isNull(), any(),
                eq("meeting.meeting.json"), eq("demo-tenant"), eq(RawSourceType.ZOOM), any(),
                eq(List.of("Dana Levi", "Esther Ora")), eq(meetingStartedAt), eq("1"), any(), eq(Triage.UNCLASSIFIED)))
                .thenReturn(event);

        MediaIntakeOrchestrator.IntakeSummary summary = orchestrator.runIntake();

        assertThat(summary.uploadedObjectKeys()).containsExactly(event.objects().get(0).objectKey());
        assertThat(summary.failedFiles()).isEmpty();
        assertThat(Files.exists(zoomDir.resolve("uploaded").resolve("meeting.meeting.json"))).isTrue();

        // The whole point of a shared correlationId: the recording and its sidecars all land
        // under the same stem in MinIO, and the published event carries that same id.
        ArgumentCaptor<String> correlationId = ArgumentCaptor.forClass(String.class);
        verify(intakeService).landMeeting(any(), any(), eq("meeting.mp3"), isNull(), isNull(), any(),
                eq("meeting.meeting.json"), eq("demo-tenant"), eq(RawSourceType.ZOOM), any(),
                eq(List.of("Dana Levi", "Esther Ora")), eq(meetingStartedAt), eq("1"),
                correlationId.capture(), eq(Triage.UNCLASSIFIED));
        assertThat(correlationId.getValue()).isNotBlank();
    }

    @Test
    void scannedDocIsLandedAsStandaloneBody() throws Exception {
        Files.write(scannedDocsDir.resolve("receipt.pdf"), "f".repeat(2000).getBytes());
        RawObjectEvent event = fakeScannedDocEvent("SCANNED_DOC/demo-tenant/2026-08-30/x.pdf");
        when(intakeService.landStandalone(any(), any(), eq("receipt.pdf"), eq("demo-tenant"),
                eq(RawSourceType.SCANNED_DOC), eq(Modality.SCANNED_DOC), any(), any(), eq(Triage.UNCLASSIFIED)))
                .thenReturn(event);

        MediaIntakeOrchestrator.IntakeSummary summary = orchestrator.runIntake();

        assertThat(summary.uploadedObjectKeys()).containsExactly(event.objects().get(0).objectKey());
        verify(eventPublisher).publish(event);
    }

    @Test
    void scannedDocIsLandedWithTheTriageItWasClassifiedAs() throws Exception {
        byte[] content = "g".repeat(2000).getBytes();
        Files.write(scannedDocsDir.resolve("blank.png"), content);
        Triage blankPage = new Triage(ScopeDecision.EXCLUDED, new Classification(
                DecidedBy.RULE, Label.fromRule(DocumentCategory.BLANK), Label.fromRule(Quality.GOOD),
                Label.fromRule(Sensitivity.NONE), "page has no visible content",
                new ClassifierInfo(ClassifierInfo.RULES_MODEL, "v1")));
        when(classificationService.triageDocument(any())).thenReturn(blankPage);
        RawObjectEvent event = fakeScannedDocEvent("SCANNED_DOC/demo-tenant/2026-08-30/x.png");
        when(intakeService.landStandalone(any(), any(), eq("blank.png"), eq("demo-tenant"),
                eq(RawSourceType.SCANNED_DOC), eq(Modality.SCANNED_DOC), any(), any(), eq(blankPage)))
                .thenReturn(event);

        orchestrator.runIntake();

        // Classified after dedup and before landing, from the file's own bytes and name - and
        // an EXCLUDED item is still landed and published, never dropped.
        ArgumentCaptor<DocumentClassificationInput> input = ArgumentCaptor.forClass(DocumentClassificationInput.class);
        verify(classificationService).triageDocument(input.capture());
        assertThat(input.getValue().content()).isEqualTo(content);
        assertThat(input.getValue().filename()).isEqualTo("blank.png");
        verify(eventPublisher).publish(event);
    }

    @Test
    void failedLandRollsBackTheDedupClaimSoARetryIsNotBlockedForever() throws Exception {
        byte[] content = "d".repeat(2000).getBytes();
        Files.write(zoomDir.resolve("broken.mp3"), content);
        when(intakeService.landMeeting(any(), any(), eq("broken.mp3"), isNull(), isNull(), isNull(),
                isNull(), eq("demo-tenant"), eq(RawSourceType.ZOOM), any(), isNull(), isNull(),
                isNull(), any(), eq(Triage.UNCLASSIFIED)))
                .thenThrow(new IllegalStateException("MinIO unreachable"));

        MediaIntakeOrchestrator.IntakeSummary summary = orchestrator.runIntake();

        assertThat(summary.failedFiles()).containsExactly("broken.mp3");
        verify(dedupIndex).forget(RawSourceType.ZOOM, "demo-tenant", sha256(content));
        verifyNoInteractions(eventPublisher);
    }

    private RawObjectEvent fakeMeetingEvent(String objectKey) {
        RawObjectRef recording = new RawObjectRef(
                Role.RECORDING, objectKey, "sha256:" + "0".repeat(64), "audio/mpeg", 2000L, Modality.AUDIO);
        return RawObjectEvent.create(RawSourceType.ZOOM, "demo-tenant", "corr-fake", Instant.now(),
                ScopeDecision.INCLUDED, 0, ChangeType.UPSERT, 100, null, null, null, List.of(recording));
    }

    private RawObjectEvent fakeScannedDocEvent(String objectKey) {
        RawObjectRef body = new RawObjectRef(
                Role.BODY, objectKey, "sha256:" + "1".repeat(64), "application/pdf", 2000L, Modality.SCANNED_DOC);
        return RawObjectEvent.create(RawSourceType.SCANNED_DOC, "demo-tenant", "corr-fake-doc", Instant.now(),
                ScopeDecision.INCLUDED, 0, ChangeType.UPSERT, 100, null, null, null, List.of(body));
    }

    private String sha256(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return "sha256:" + HexFormat.of().formatHex(digest.digest(content));
    }
}
