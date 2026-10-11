package com.rnd.bridge.media.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.media.meeting.MeetingMetadata;
import com.rnd.bridge.media.meeting.ParticipantActivity;
import com.rnd.bridge.raw.model.Classification;
import com.rnd.bridge.raw.model.DecidedBy;
import com.rnd.bridge.raw.model.DocumentCategory;
import com.rnd.bridge.raw.model.MeetingCategory;
import com.rnd.bridge.raw.model.Quality;
import com.rnd.bridge.raw.model.Sensitivity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelMediaClassifierTest {

    private static final String TRANSCRIPT =
            "WEBVTT\n\n1\n00:00:01.000 --> 00:00:04.000\nDana: let's plan the next sprint and split the work.\n";

    private final RecordingChatClient chatClient = new RecordingChatClient();
    private final ModelMediaClassifier classifier = new ModelMediaClassifier(
            chatClient, new ObjectMapper(), ClassificationTestSupport.properties(true, "key"));

    @Test
    void parsesMeetingVerdictIntoClassification() {
        chatClient.reply = """
                {"category": "PLANNING", "category_confidence": 0.93,
                 "quality": "GOOD", "quality_confidence": 0.9,
                 "sensitivity": "NONE", "sensitivity_confidence": 0.8,
                 "reason": "sprint planning discussion"}""";

        Classification result = classifier.classifyMeeting(new MeetingClassificationInput(metadata(), TRANSCRIPT)).orElseThrow();

        assertThat(result.decidedBy()).isEqualTo(DecidedBy.MODEL);
        assertThat(result.category().value()).isEqualTo(MeetingCategory.PLANNING);
        assertThat(result.category().confidence()).isEqualTo(0.93);
        assertThat(result.quality().value()).isEqualTo(Quality.GOOD);
        assertThat(result.sensitivity().value()).isEqualTo(Sensitivity.NONE);
        assertThat(result.reason()).isEqualTo("sprint planning discussion");
        assertThat(result.classifier().model()).isEqualTo("test-model");
        assertThat(result.classifier().promptVersion()).isEqualTo("v1");
    }

    @Test
    void sendsSpokenTextAndHeadcountButNoParticipantNamesOrTimestamps() {
        chatClient.reply = verdict("PLANNING");

        classifier.classifyMeeting(new MeetingClassificationInput(metadata(), TRANSCRIPT));

        assertThat(chatClient.userText).contains("Topic: Sprint planning", "Participants: 2",
                "let's plan the next sprint");
        assertThat(chatClient.userText).doesNotContain("Dana Levi", "Yossi Cohen", "-->", "WEBVTT");
        assertThat(chatClient.image).isNull();
    }

    @Test
    void toleratesCodeFencesAroundTheJson() {
        chatClient.reply = "```json\n" + verdict("STANDUP") + "\n```";

        assertThat(classifier.classifyMeeting(new MeetingClassificationInput(null, TRANSCRIPT))
                .orElseThrow().category().value()).isEqualTo(MeetingCategory.STANDUP);
    }

    @Test
    void unknownCategoryBecomesOther() {
        chatClient.reply = verdict("BRAINSTORM");

        assertThat(classifier.classifyMeeting(new MeetingClassificationInput(null, TRANSCRIPT))
                .orElseThrow().category().value()).isEqualTo(MeetingCategory.OTHER);
    }

    @Test
    void invalidQualityIsRejected() {
        chatClient.reply = verdict("PLANNING").replace("\"GOOD\"", "\"EXCELLENT\"");

        assertThatThrownBy(() -> classifier.classifyMeeting(new MeetingClassificationInput(null, TRANSCRIPT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingConfidenceIsRejectedNotDefaultedToZero() {
        chatClient.reply = verdict("PLANNING").replace("\"category_confidence\": 0.9,", "");

        assertThatThrownBy(() -> classifier.classifyMeeting(new MeetingClassificationInput(null, TRANSCRIPT)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void replyWithoutJsonIsRejected() {
        chatClient.reply = "Sorry, I can't help with that.";

        assertThatThrownBy(() -> classifier.classifyMeeting(new MeetingClassificationInput(null, TRANSCRIPT)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void meetingWithNothingToJudgeIsNotSentToTheModel() {
        assertThat(classifier.classifyMeeting(new MeetingClassificationInput(null, null))).isEmpty();
        assertThat(chatClient.calls).isZero();
    }

    @Test
    void documentIsSentAsPngImage() {
        chatClient.reply = verdict("INVOICE");

        Classification result = classifier.classifyDocument(
                new DocumentClassificationInput(ClassificationTestSupport.textPagePng(), "scan.png")).orElseThrow();

        assertThat(result.category().value()).isEqualTo(DocumentCategory.INVOICE);
        assertThat(chatClient.image).startsWith(0x89, 'P', 'N', 'G');
        assertThat(chatClient.systemPrompt).contains("INVOICE", "SCIENTIFIC_PUBLICATION", "BLANK");
    }

    @Test
    void nonImageDocumentIsNotSentToTheModel() {
        assertThat(classifier.classifyDocument(new DocumentClassificationInput("%PDF-1.7".getBytes(), "scan.pdf"))).isEmpty();
        assertThat(chatClient.calls).isZero();
    }

    private static String verdict(String category) {
        return """
                {"category": "%s", "category_confidence": 0.9,
                 "quality": "GOOD", "quality_confidence": 0.9,
                 "sensitivity": "LOW", "sensitivity_confidence": 0.9,
                 "reason": "generic reason"}""".formatted(category);
    }

    private static MeetingMetadata metadata() {
        return new MeetingMetadata("1", "Sprint planning", "dana@example.com",
                Instant.parse("2026-10-11T09:00:00Z"), Instant.parse("2026-10-11T09:30:00Z"),
                List.of(new ParticipantActivity("a", "Dana Levi", null, null),
                        new ParticipantActivity("b", "Yossi Cohen", null, null)));
    }

    private static final class RecordingChatClient implements ChatCompletionClient {
        String reply;
        String systemPrompt;
        String userText;
        byte[] image;
        int calls;

        @Override
        public String complete(String systemPrompt, String userText, byte[] pngImage) {
            this.systemPrompt = systemPrompt;
            this.userText = userText;
            this.image = pngImage;
            calls++;
            return reply;
        }
    }
}
