package com.rnd.bridge.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private MediaIntakeOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        when(dedupIndex.checkAndRecord(any(), any(), any())).thenReturn(false);
        orchestrator = new MediaIntakeOrchestrator(
                intakeService, eventPublisher, dedupIndex,
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
        RawObjectEvent event = fakeEvent("MEETING/demo-tenant/2026-08-30/x.mp3");
        when(intakeService.land(any(), any(), eq("meeting.mp3"), eq("demo-tenant"),
                eq(RawSourceType.MEETING), eq(Modality.AUDIO), any(), isNull()))
                .thenReturn(event);

        MediaIntakeOrchestrator.IntakeSummary summary = orchestrator.runIntake();

        assertThat(summary.uploadedObjectKeys()).containsExactly(event.objectKey());
        assertThat(Files.exists(zoomDir.resolve("uploaded").resolve("meeting.mp3"))).isTrue();
        verify(eventPublisher).publish(event);
        verify(dedupIndex).checkAndRecord(eq(RawSourceType.MEETING), eq("demo-tenant"), any());
    }

    @Test
    void duplicateContentIsSkippedButStillMovedToUploaded() throws Exception {
        byte[] content = "b".repeat(2000).getBytes();
        Files.write(zoomDir.resolve("dup.mp3"), content);
        when(dedupIndex.checkAndRecord(eq(RawSourceType.MEETING), eq("demo-tenant"), eq(sha256(content))))
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
        String transcriptKey = "MEETING/demo-tenant/2026-08-30/t.vtt";
        when(intakeService.landTranscript(any(), eq("meeting.vtt"), eq("demo-tenant"),
                eq(RawSourceType.MEETING), any()))
                .thenReturn(transcriptKey);
        RawObjectEvent event = fakeEvent("MEETING/demo-tenant/2026-08-30/x.mp3");
        when(intakeService.land(any(), any(), eq("meeting.mp3"), eq("demo-tenant"),
                eq(RawSourceType.MEETING), eq(Modality.AUDIO), any(), eq(transcriptKey)))
                .thenReturn(event);

        MediaIntakeOrchestrator.IntakeSummary summary = orchestrator.runIntake();

        assertThat(summary.uploadedObjectKeys()).containsExactly(event.objectKey());
        assertThat(summary.failedFiles()).isEmpty();
        assertThat(Files.exists(zoomDir.resolve("uploaded").resolve("meeting.vtt"))).isTrue();
        verify(intakeService).landTranscript(any(), eq("meeting.vtt"), eq("demo-tenant"),
                eq(RawSourceType.MEETING), any());
    }

    @Test
    void failedLandRollsBackTheDedupClaimSoARetryIsNotBlockedForever() throws Exception {
        byte[] content = "d".repeat(2000).getBytes();
        Files.write(zoomDir.resolve("broken.mp3"), content);
        when(intakeService.land(any(), any(), eq("broken.mp3"), eq("demo-tenant"),
                eq(RawSourceType.MEETING), eq(Modality.AUDIO), any(), isNull()))
                .thenThrow(new IllegalStateException("MinIO unreachable"));

        MediaIntakeOrchestrator.IntakeSummary summary = orchestrator.runIntake();

        assertThat(summary.failedFiles()).containsExactly("broken.mp3");
        verify(dedupIndex).forget(RawSourceType.MEETING, "demo-tenant", sha256(content));
        verifyNoInteractions(eventPublisher);
    }

    private RawObjectEvent fakeEvent(String objectKey) {
        return RawObjectEvent.create(objectKey, RawSourceType.MEETING, "demo-tenant", Instant.now(),
                ScopeDecision.INCLUDED, "sha256:" + "0".repeat(64), Modality.AUDIO, null,
                "audio/mpeg", 2000L, null, null, 0, 0, 0, ChangeType.UPSERT);
    }

    private String sha256(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return "sha256:" + HexFormat.of().formatHex(digest.digest(content));
    }
}
