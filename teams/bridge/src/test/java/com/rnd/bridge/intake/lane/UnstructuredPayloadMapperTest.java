package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.SourceRecordRefMapper;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.scoping.AllowlistScopingFilter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers the ROADMAP.md §3.3/Drive-plan contract fix: changeType=DELETE_REVIEW must produce an
 * empty objects list - per RawObjectEvent's own contract (never building a RawObjectRef, never
 * calling SourceRecordRefMapper, for that case) - while an ordinary UPSERT keeps building one
 * ref per record in the group exactly as before.
 */
class UnstructuredPayloadMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    private final AllowlistScopingFilter scopingFilter = mock(AllowlistScopingFilter.class);
    private final SourceRecordRefMapper refMapper = mock(SourceRecordRefMapper.class);
    private final UnstructuredPayloadMapper mapper = new UnstructuredPayloadMapper(scopingFilter, refMapper);

    @Test
    void deleteReviewProducesAnEmptyObjectsListAndNeverCallsRefMapper() {
        SourceRecord trashedRecord = new SourceRecord(
                RawSourceType.DRIVE, "kamatech", "file-1", "corr-1", NOW, null, List.of(), null, null,
                "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(), 1, ChangeType.DELETE_REVIEW);
        when(scopingFilter.decide(trashedRecord)).thenReturn(ScopeDecision.INCLUDED);

        RawObjectEvent event = mapper.map(List.of(trashedRecord), NOW);

        assertThat(event.objects()).isEmpty();
        assertThat(event.changeType()).isEqualTo(ChangeType.DELETE_REVIEW);
        verify(refMapper, never()).toRef(any(), any());
    }

    @Test
    void slackSourcedDeleteReviewAlsoProducesAnEmptyObjectsListLikeEverySource() {
        // Explicit SLACK regression case (docs/plans/slack-message-deleted.md §7) - this generic,
        // changeType-based enforcement (no source-specific branching) already covers Slack for
        // free, same as Calendar/Drive; this test only proves it, it changes no code.
        SourceRecord deletedTranscript = new SourceRecord(
                RawSourceType.SLACK, "kamatech", "chat-a|corr-1", "corr-1", NOW, null, List.of(), null, null,
                "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(), 1, ChangeType.DELETE_REVIEW);
        when(scopingFilter.decide(deletedTranscript)).thenReturn(ScopeDecision.INCLUDED);

        RawObjectEvent event = mapper.map(List.of(deletedTranscript), NOW);

        assertThat(event.objects()).isEmpty();
        assertThat(event.changeType()).isEqualTo(ChangeType.DELETE_REVIEW);
        verify(refMapper, never()).toRef(any(), any());
    }

    @Test
    void upsertStillBuildsOneRefPerRecordInTheGroup() {
        SourceRecord record = new SourceRecord(
                RawSourceType.DRIVE, "kamatech", "file-1", "corr-1", NOW, "malki@kamatech.org",
                List.of("malki@kamatech.org"), "name", "desc", "bytes".getBytes(StandardCharsets.UTF_8), null, null,
                0, 0, List.of(), 0);
        when(scopingFilter.decide(record)).thenReturn(ScopeDecision.INCLUDED);
        RawObjectRef ref = new RawObjectRef(
                Role.BODY, "DRIVE/kamatech/2026-10-18/x.json", "sha256:" + "0".repeat(64), "application/octet-stream",
                5L, Modality.TEXT);
        when(refMapper.toRef(record, NOW)).thenReturn(ref);

        RawObjectEvent event = mapper.map(List.of(record), NOW);

        assertThat(event.objects()).containsExactly(ref);
        assertThat(event.changeType()).isEqualTo(ChangeType.UPSERT);
    }

    @Test
    void sourceRefIsCarriedFromThePrimaryRecordOntoTheEvent() {
        SourceRecord record = new SourceRecord(
                RawSourceType.DRIVE, "kamatech", "file-1", "corr-1", NOW, "malki@kamatech.org",
                List.of("malki@kamatech.org"), "name", "desc", "bytes".getBytes(StandardCharsets.UTF_8), null, null,
                0, 0, List.of(), 0).withSourceRef("drive-file-id-1");
        when(scopingFilter.decide(record)).thenReturn(ScopeDecision.INCLUDED);
        RawObjectRef ref = new RawObjectRef(
                Role.BODY, "DRIVE/kamatech/2026-10-18/x.json", "sha256:" + "0".repeat(64), "application/octet-stream",
                5L, Modality.TEXT);
        when(refMapper.toRef(record, NOW)).thenReturn(ref);

        RawObjectEvent event = mapper.map(List.of(record), NOW);

        assertThat(event.sourceRef()).isEqualTo("drive-file-id-1");
    }

    @Test
    void nullSourceRefOnThePrimaryRecordProducesANullSourceRefOnTheEvent() {
        SourceRecord record = new SourceRecord(
                RawSourceType.WHATSAPP, "kamatech", "chat-1", "corr-1", NOW, "malki",
                List.of("malki"), null, "hi", "bytes".getBytes(StandardCharsets.UTF_8), null, null,
                0, 0, List.of(), 0);
        when(scopingFilter.decide(record)).thenReturn(ScopeDecision.INCLUDED);
        RawObjectRef ref = new RawObjectRef(
                Role.BODY, "WHATSAPP/kamatech/2026-10-18/x.json", "sha256:" + "0".repeat(64),
                "application/octet-stream", 5L, Modality.TEXT);
        when(refMapper.toRef(record, NOW)).thenReturn(ref);

        RawObjectEvent event = mapper.map(List.of(record), NOW);

        assertThat(event.sourceRef()).isNull();
    }
}
