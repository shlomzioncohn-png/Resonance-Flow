package com.rnd.bridge.media.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.rnd.bridge.media.classification.document.DocumentScan;
import com.rnd.bridge.media.meeting.MeetingMetadata;
import com.rnd.bridge.raw.model.Classification;
import com.rnd.bridge.raw.model.ClassifierInfo;
import com.rnd.bridge.raw.model.DecidedBy;
import com.rnd.bridge.raw.model.DocumentCategory;
import com.rnd.bridge.raw.model.MeetingCategory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ClassificationRulesTest {

    private static final String REAL_TRANSCRIPT = """
            WEBVTT

            1
            00:00:01.000 --> 00:00:04.000
            Let's go over the release plan for next sprint and who picks up what.
            """;

    private final ClassificationRules rules =
            new ClassificationRules(ClassificationTestSupport.properties(true, ""));

    @Test
    void meetingShorterThanTheMinimumIsATest() {
        MeetingMetadata metadata = metadata(Instant.parse("2026-10-11T09:00:00Z"), Instant.parse("2026-10-11T09:00:40Z"));

        Classification result = rules.classifyMeeting(new MeetingClassificationInput(metadata, REAL_TRANSCRIPT)).orElseThrow();

        assertThat(result.decidedBy()).isEqualTo(DecidedBy.RULE);
        assertThat(result.category().value()).isEqualTo(MeetingCategory.TEST);
        assertThat(result.category().confidence()).isEqualTo(1.0);
        assertThat(result.classifier().model()).isEqualTo(ClassifierInfo.RULES_MODEL);
    }

    @Test
    void emptyTranscriptOfANormalLengthMeetingIsNotATest() {
        // A failed transcription is not evidence the meeting was empty.
        MeetingMetadata metadata = metadata(Instant.parse("2026-10-11T09:00:00Z"), Instant.parse("2026-10-11T09:30:00Z"));

        assertThat(rules.classifyMeeting(new MeetingClassificationInput(metadata, "WEBVTT\n"))).isEmpty();
    }

    @Test
    void normalMeetingIsLeftForTheModel() {
        MeetingMetadata metadata = metadata(Instant.parse("2026-10-11T09:00:00Z"), Instant.parse("2026-10-11T09:30:00Z"));

        assertThat(rules.classifyMeeting(new MeetingClassificationInput(metadata, REAL_TRANSCRIPT))).isEmpty();
    }

    @Test
    void meetingWithNoMetadataAndNoTranscriptIsLeftUndecided() {
        assertThat(rules.classifyMeeting(new MeetingClassificationInput(null, null))).isEmpty();
    }

    @Test
    void documentWhosePagesWereAllCheckedAndBlankIsBlank() {
        DocumentScan scan = new DocumentScan(DocumentScan.Outcome.ALL_BLANK, 3, 3, List.of());

        Classification result = rules.classifyDocument(scan).orElseThrow();

        assertThat(result.category().value()).isEqualTo(DocumentCategory.BLANK);
        assertThat(result.decidedBy()).isEqualTo(DecidedBy.RULE);
        assertThat(result.reason()).isEqualTo("all 3 pages have no visible content");
    }

    @ParameterizedTest
    @EnumSource(value = DocumentScan.Outcome.class, names = "ALL_BLANK", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherScanOutcomeIsLeftUndecided(DocumentScan.Outcome outcome) {
        DocumentScan scan = outcome == DocumentScan.Outcome.CONTENT_FOUND
                ? ClassificationTestSupport.contentScan(4, 2)
                : new DocumentScan(outcome, 10, 5, List.of());

        assertThat(rules.classifyDocument(scan)).isEmpty();
    }

    private static MeetingMetadata metadata(Instant startedAt, Instant endedAt) {
        return new MeetingMetadata("1", "Sprint planning", null, startedAt, endedAt, List.of());
    }
}
