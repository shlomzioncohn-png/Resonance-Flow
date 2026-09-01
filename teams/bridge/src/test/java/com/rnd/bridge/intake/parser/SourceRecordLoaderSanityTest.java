package com.rnd.bridge.intake.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.parser.calendar.FixtureCalendarSourceReader;
import com.rnd.bridge.intake.parser.drive.FixtureDriveSourceReader;
import com.rnd.bridge.intake.parser.email.FixtureEmailSourceReader;
import com.rnd.bridge.intake.parser.slack.FixtureSlackSourceReader;
import com.rnd.bridge.intake.parser.whatsapp.FixtureWhatsAppSourceReader;
import com.rnd.bridge.intake.service.ChatSessionGrouper;
import com.rnd.bridge.raw.model.RawSourceType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Throwaway sanity check confirming the loader + grouper actually parse the fixtures
 * correctly end to end, before wiring the rest of the pipeline on top of them.
 */
class SourceRecordLoaderSanityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SourceRecordLoader loader = new SourceRecordLoader(
            new FixtureEmailSourceReader(objectMapper),
            new FixtureDriveSourceReader(objectMapper),
            new FixtureCalendarSourceReader(objectMapper),
            new FixtureSlackSourceReader(objectMapper),
            new FixtureWhatsAppSourceReader(new PathMatchingResourcePatternResolver()),
            new ChatSessionGrouper(new ChatSessionProperties(45)));

    @Test
    void loadsAllFourEmailBodiesPlusTwoAttachmentRecords() {
        List<SourceRecord> records = loader.load(RawSourceType.EMAIL, ReadCursor.full());
        List<SourceRecord> bodies = records.stream().filter(r -> r.attachmentFilename() == null).toList();

        assertThat(records).hasSize(6); // 4 email bodies + 2 attachments split off Ruth's email
        assertThat(bodies).hasSize(4);
        assertThat(bodies.get(0).sender()).isEqualTo("hadas@kamatech.org");
        assertThat(bodies.get(0).subject()).isEqualTo("PR review needed");
    }

    @Test
    void loadsDriveWithNullDescriptionForImage() {
        List<SourceRecord> records = loader.load(RawSourceType.DRIVE, ReadCursor.full());
        assertThat(records).hasSize(2);
        SourceRecord image = records.stream().filter(r -> r.naturalId().equals("9f8e7d6c5b")).findFirst().orElseThrow();
        assertThat(image.body()).isNull();
        assertThat(image.mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void emailWithTwoAttachmentsProducesTwoRecordsBothSharingTheBodysCorrelationId() {
        List<SourceRecord> records = loader.load(RawSourceType.EMAIL, ReadCursor.full());
        SourceRecord body = records.stream()
                .filter(r -> "CRM deal update".equals(r.subject()))
                .findFirst().orElseThrow();
        SourceRecord pdfAttachment = records.stream()
                .filter(r -> "deal-invoice-scan.pdf".equals(r.attachmentFilename()))
                .findFirst().orElseThrow();
        SourceRecord audioAttachment = records.stream()
                .filter(r -> "deal-voice-memo.m4a".equals(r.attachmentFilename()))
                .findFirst().orElseThrow();

        assertThat(pdfAttachment.mimeType()).isEqualTo("application/pdf");
        assertThat(audioAttachment.mimeType()).isEqualTo("audio/mp4");
        assertThat(pdfAttachment.correlationId()).isEqualTo(body.correlationId());
        assertThat(audioAttachment.correlationId()).isEqualTo(body.correlationId());
        assertThat(pdfAttachment.naturalId()).isNotEqualTo(audioAttachment.naturalId());
        assertThat(pdfAttachment.rawContent()).isNotEmpty();
        assertThat(body.mimeType()).isNull();
    }

    @Test
    void loadsCalendarEventsPlusOneAttachmentOnTheSprintReviewEvent() {
        List<SourceRecord> records = loader.load(RawSourceType.CALENDAR, ReadCursor.full());
        List<SourceRecord> bodies = records.stream().filter(r -> r.attachmentFilename() == null).toList();

        assertThat(records).hasSize(3); // 2 event bodies + 1 agenda attachment
        assertThat(bodies).hasSize(2);

        SourceRecord sprintReview = bodies.stream()
                .filter(r -> "Sprint review meeting".equals(r.subject()))
                .findFirst().orElseThrow();
        SourceRecord agenda = records.stream()
                .filter(r -> "sprint-review-agenda.pdf".equals(r.attachmentFilename()))
                .findFirst().orElseThrow();

        assertThat(agenda.mimeType()).isEqualTo("application/pdf");
        assertThat(agenda.correlationId()).isEqualTo(sprintReview.correlationId());
        assertThat(agenda.rawContent()).isNotEmpty();
    }

    @Test
    void slackMessagesSplitIntoTwoSessionsAcrossTheFiveHourGap() {
        List<SourceRecord> records = loader.load(RawSourceType.SLACK, ReadCursor.full());
        List<SourceRecord> transcripts = records.stream().filter(r -> r.attachmentFilename() == null).toList();

        assertThat(transcripts).hasSize(2);
        SourceRecord firstSession = transcripts.stream()
                .filter(s -> s.body().contains("מישהו יכול לבדוק"))
                .findFirst().orElseThrow();
        assertThat(firstSession.body()).contains("ruth: מישהו יכול לבדוק את ה-deal של לקוח X?");
        assertThat(firstSession.body()).contains("malki: כן, אני על זה");
    }

    @Test
    void slackFileShareBecomesItsOwnAttachmentRecordFromTheRealFilesArray() {
        List<SourceRecord> records = loader.load(RawSourceType.SLACK, ReadCursor.full());

        assertThat(records).hasSize(3); // 2 session transcripts + 1 attachment from the "files" message
        SourceRecord attachment = records.stream()
                .filter(r -> "contract-scan.jpg".equals(r.attachmentFilename()))
                .findFirst().orElseThrow();
        SourceRecord firstSession = records.stream()
                .filter(s -> s.attachmentFilename() == null && s.body().contains("מישהו יכול לבדוק"))
                .findFirst().orElseThrow();

        assertThat(attachment.mimeType()).isEqualTo("image/jpeg");
        assertThat(attachment.correlationId()).isEqualTo(firstSession.correlationId());
        assertThat(attachment.rawContent()).isNotEmpty();
    }

    @Test
    void whatsAppFamilyChatIsTextOnlyWorkChatAlsoHasAnAttachment() {
        List<SourceRecord> records = loader.load(RawSourceType.WHATSAPP, ReadCursor.full());
        // family: 1 body record. work: 1 body record + 1 attachment record. Total 3.
        assertThat(records).hasSize(3);
        assertThat(records).anyMatch(s -> s.naturalId().startsWith("whatsapp-family-sample|"));
        assertThat(records).anyMatch(s -> s.naturalId().startsWith("whatsapp-work-sample|"));

        SourceRecord family = records.stream()
                .filter(s -> s.naturalId().startsWith("whatsapp-family-sample|"))
                .findFirst().orElseThrow();
        assertThat(family.body()).contains("אמא: מי מביא עוגה?");
        assertThat(family.body()).contains("מלכי: אני אביא");
        assertThat(family.mimeType()).isNull();

        SourceRecord workBody = records.stream()
                .filter(s -> s.naturalId().startsWith("whatsapp-work-sample|") && s.attachmentFilename() == null)
                .findFirst().orElseThrow();
        SourceRecord workAttachment = records.stream()
                .filter(s -> s.naturalId().startsWith("whatsapp-work-sample|") && s.attachmentFilename() != null)
                .findFirst().orElseThrow();
        assertThat(workAttachment.mimeType()).isEqualTo("audio/opus");
        assertThat(workAttachment.attachmentFilename()).isEqualTo("voice-note.opus");
        assertThat(workAttachment.correlationId()).isEqualTo(workBody.correlationId());
        assertThat(workAttachment.rawContent()).isNotEmpty();
    }
}
