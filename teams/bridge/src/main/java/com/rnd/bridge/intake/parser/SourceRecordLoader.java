package com.rnd.bridge.intake.parser;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.parser.calendar.CalendarSourceReader;
import com.rnd.bridge.intake.parser.drive.DriveSourceReader;
import com.rnd.bridge.intake.parser.email.EmailSourceReader;
import com.rnd.bridge.intake.parser.slack.SlackSourceReader;
import com.rnd.bridge.intake.parser.whatsapp.WhatsAppSourceReader;
import com.rnd.bridge.intake.service.ChatSessionGrouper;
import com.rnd.bridge.raw.model.RawSourceType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Replaces the old FixtureSourceLoader as UnstructuredIntakeService's single entry point —
 * same load(RawSourceType) -> List<SourceRecord> contract. Each source now has its own
 * Fixture.../Real...Reader pair (bridge.intake.<source>.mode selects which bean is active —
 * see plan.md), so this class only dispatches and, for chat sources, groups raw messages
 * into sessions via ChatSessionGrouper before returning.
 */
@Component
public class SourceRecordLoader {

    private final EmailSourceReader emailReader;
    private final DriveSourceReader driveReader;
    private final CalendarSourceReader calendarReader;
    private final SlackSourceReader slackReader;
    private final WhatsAppSourceReader whatsAppReader;
    private final ChatSessionGrouper chatSessionGrouper;

    public SourceRecordLoader(
            EmailSourceReader emailReader,
            DriveSourceReader driveReader,
            CalendarSourceReader calendarReader,
            SlackSourceReader slackReader,
            WhatsAppSourceReader whatsAppReader,
            ChatSessionGrouper chatSessionGrouper) {
        this.emailReader = emailReader;
        this.driveReader = driveReader;
        this.calendarReader = calendarReader;
        this.slackReader = slackReader;
        this.whatsAppReader = whatsAppReader;
        this.chatSessionGrouper = chatSessionGrouper;
    }

    public List<SourceRecord> load(RawSourceType sourceType) {
        return switch (sourceType) {
            case EMAIL -> emailReader.read();
            case DRIVE -> driveReader.read();
            case CALENDAR -> calendarReader.read();
            case SLACK -> chatSessionGrouper.group(slackReader.read());
            case WHATSAPP -> chatSessionGrouper.group(whatsAppReader.read());
            case MEETING, SCANNED_DOC -> throw new IllegalArgumentException(
                    sourceType + " is produced by the meeting/doc/audio lane, not this one");
        };
    }
}
