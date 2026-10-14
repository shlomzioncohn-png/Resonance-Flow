package com.rnd.bridge.intake.parser;

import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.parser.calendar.CalendarSourceReader;
import com.rnd.bridge.intake.parser.drive.DriveSourceReader;
import com.rnd.bridge.intake.parser.email.EmailSourceReader;
import com.rnd.bridge.intake.parser.slack.SlackSourceReader;
import com.rnd.bridge.intake.parser.whatsapp.WhatsAppSourceReader;
import com.rnd.bridge.intake.service.ChatGroupStateReconciler;
import com.rnd.bridge.intake.service.ChatSessionGrouper;
import com.rnd.bridge.intake.service.ChatSourceRecordMapper;
import com.rnd.bridge.raw.model.RawSourceType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Replaces the old FixtureSourceLoader as the single entry point each UnstructuredIntakeLane
 * reads through (via SourceRecordReaderAdapter, one per RawSourceType) — same
 * load(RawSourceType) -> List<SourceRecord> contract. Each source now has its own
 * Fixture.../Real...Reader pair (bridge.intake.<source>.mode selects which bean is active —
 * see plan.md), so this class only dispatches and, for chat sources, groups raw messages
 * into sessions before returning.
 * Slack goes through ChatGroupStateReconciler rather than ChatSessionGrouper directly — Slack
 * messages carry a stable id, so a group can be recognized and extended across runs (see
 * ChatGroupStateReconciler's javadoc), and the reconciler owns building each group's
 * SourceRecords itself (via ChatSourceRecordMapper) once it's resolved persisted state.
 * WhatsApp has no such id, so there's nothing to persist/match against - this class is the
 * WhatsApp orchestrator instead, grouping via ChatSessionGrouper.groupIntoSessions() and then
 * mapping each session straight to SourceRecords via ChatSourceRecordMapper.
 */
@Component
public class SourceRecordLoader {

    private final EmailSourceReader emailReader;
    private final DriveSourceReader driveReader;
    private final CalendarSourceReader calendarReader;
    private final SlackSourceReader slackReader;
    private final WhatsAppSourceReader whatsAppReader;
    private final ChatSessionGrouper chatSessionGrouper;
    private final ChatSourceRecordMapper chatSourceRecordMapper;
    private final ChatGroupStateReconciler chatGroupStateReconciler;

    public SourceRecordLoader(
            EmailSourceReader emailReader,
            DriveSourceReader driveReader,
            CalendarSourceReader calendarReader,
            SlackSourceReader slackReader,
            WhatsAppSourceReader whatsAppReader,
            ChatSessionGrouper chatSessionGrouper,
            ChatSourceRecordMapper chatSourceRecordMapper,
            ChatGroupStateReconciler chatGroupStateReconciler) {
        this.emailReader = emailReader;
        this.driveReader = driveReader;
        this.calendarReader = calendarReader;
        this.slackReader = slackReader;
        this.whatsAppReader = whatsAppReader;
        this.chatSessionGrouper = chatSessionGrouper;
        this.chatSourceRecordMapper = chatSourceRecordMapper;
        this.chatGroupStateReconciler = chatGroupStateReconciler;
    }

    public List<SourceRecord> load(RawSourceType sourceType, ReadCursor cursor) {
        return switch (sourceType) {
            case EMAIL -> emailReader.read();
            case DRIVE -> driveReader.read();
            // Slack and Calendar honor the cursor today (stage 2, per plan.md "Cursor
            // persistence") - Email/Drive/WhatsApp still read everything every run until their
            // Real reader adds since-filtering of its own.
            case CALENDAR -> calendarReader.read(cursor).records();
            case SLACK -> chatGroupStateReconciler.group(slackReader.read(cursor.since()));
            case WHATSAPP -> chatSessionGrouper.groupIntoSessions(whatsAppReader.read()).stream()
                    .flatMap(session -> chatSourceRecordMapper.toSourceRecords(session).stream())
                    .toList();
            case MEETING, SCANNED_DOC -> throw new IllegalArgumentException(
                    sourceType + " is produced by the meeting/doc/audio lane, not this one");
        };
    }
}
