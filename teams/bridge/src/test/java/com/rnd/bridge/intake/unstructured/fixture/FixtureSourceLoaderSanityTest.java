package com.rnd.bridge.intake.unstructured.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.intake.unstructured.config.ChatSessionProperties;
import com.rnd.bridge.intake.unstructured.dto.SourceRecord;
import com.rnd.bridge.intake.unstructured.dto.SourceType;
import com.rnd.bridge.intake.unstructured.service.ChatSessionGrouper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Throwaway sanity check confirming the loader + grouper actually parse the fixtures
 * correctly end to end, before wiring the rest of the pipeline on top of them.
 */
class FixtureSourceLoaderSanityTest {

    private final FixtureSourceLoader loader = new FixtureSourceLoader(
            new ObjectMapper(),
            new ChatSessionGrouper(new ChatSessionProperties(45)),
            new PathMatchingResourcePatternResolver());

    @Test
    void loadsAllFourEmails() {
        List<SourceRecord> records = loader.load(SourceType.EMAIL);
        assertThat(records).hasSize(4);
        assertThat(records.get(0).sender()).isEqualTo("hadas@kamatech.org");
        assertThat(records.get(0).subject()).isEqualTo("PR review needed");
    }

    @Test
    void loadsDriveWithNullDescriptionForImage() {
        List<SourceRecord> records = loader.load(SourceType.DRIVE_DOC);
        assertThat(records).hasSize(2);
        SourceRecord image = records.stream().filter(r -> r.naturalId().equals("9f8e7d6c5b")).findFirst().orElseThrow();
        assertThat(image.body()).isNull();
        assertThat(image.mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void loadsCalendarEvents() {
        List<SourceRecord> records = loader.load(SourceType.CALENDAR);
        assertThat(records).hasSize(2);
    }

    @Test
    void slackMessagesSplitIntoTwoSessionsAcrossTheFiveHourGap() {
        List<SourceRecord> sessions = loader.load(SourceType.SLACK);
        assertThat(sessions).hasSize(2);
        SourceRecord firstSession = sessions.stream()
                .filter(s -> s.body().contains("מישהו יכול לבדוק"))
                .findFirst().orElseThrow();
        assertThat(firstSession.body()).contains("ruth: מישהו יכול לבדוק את ה-deal של לקוח X?");
        assertThat(firstSession.body()).contains("malki: כן, אני על זה");
    }

    @Test
    void whatsAppFilesEachProduceOneSessionWithChatIdFromFilename() {
        List<SourceRecord> sessions = loader.load(SourceType.WHATSAPP);
        assertThat(sessions).hasSize(2);
        assertThat(sessions).anyMatch(s -> s.naturalId().startsWith("whatsapp-family-sample|"));
        assertThat(sessions).anyMatch(s -> s.naturalId().startsWith("whatsapp-work-sample|"));
        SourceRecord family = sessions.stream()
                .filter(s -> s.naturalId().startsWith("whatsapp-family-sample|"))
                .findFirst().orElseThrow();
        assertThat(family.body()).contains("אמא: מי מביא עוגה?");
        assertThat(family.body()).contains("מלכי: אני אביא");
    }
}
