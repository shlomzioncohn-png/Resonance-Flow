package com.rnd.bridge.media.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.rnd.bridge.media.meeting.MeetingMetadata;
import com.rnd.bridge.raw.model.Classification;
import com.rnd.bridge.raw.model.ClassifierInfo;
import com.rnd.bridge.raw.model.DecidedBy;
import com.rnd.bridge.raw.model.DocumentCategory;
import com.rnd.bridge.raw.model.MeetingCategory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

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
    void transcriptWithOnlyTimestampsIsATest() {
        String noSpeech = "WEBVTT\n\n1\n00:00:01.000 --> 00:00:03.000\n\n2\n00:00:04.000 --> 00:00:05.000\nhi\n";

        Classification result = rules.classifyMeeting(new MeetingClassificationInput(null, noSpeech)).orElseThrow();

        assertThat(result.category().value()).isEqualTo(MeetingCategory.TEST);
        assertThat(result.reason()).isEqualTo("transcript has no spoken content");
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
    void blankPageIsBlank() {
        Classification result = rules.classifyDocument(
                new DocumentClassificationInput(ClassificationTestSupport.blankPagePng(), "scan.png")).orElseThrow();

        assertThat(result.category().value()).isEqualTo(DocumentCategory.BLANK);
        assertThat(result.decidedBy()).isEqualTo(DecidedBy.RULE);
    }

    @Test
    void pageWithContentIsLeftForTheModel() {
        assertThat(rules.classifyDocument(
                new DocumentClassificationInput(ClassificationTestSupport.textPagePng(), "scan.png"))).isEmpty();
    }

    @Test
    void nonImageDocumentIsLeftUndecided() {
        assertThat(rules.classifyDocument(
                new DocumentClassificationInput("%PDF-1.7 not an image".getBytes(), "scan.pdf"))).isEmpty();
    }

    private static MeetingMetadata metadata(Instant startedAt, Instant endedAt) {
        return new MeetingMetadata("1", "Sprint planning", null, startedAt, endedAt, List.of());
    }
}
