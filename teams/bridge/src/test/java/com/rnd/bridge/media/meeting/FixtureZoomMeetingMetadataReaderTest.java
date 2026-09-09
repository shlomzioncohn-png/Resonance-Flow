package com.rnd.bridge.media.meeting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class FixtureZoomMeetingMetadataReaderTest {

    @TempDir
    Path tempDir;

    private final FixtureZoomMeetingMetadataReader reader = new FixtureZoomMeetingMetadataReader(new ObjectMapper());

    @Test
    void parsesTheZoomSampleFixture() throws Exception {
        Path sidecar = tempDir.resolve("meeting.meeting.json");
        try (InputStream in = new ClassPathResource("fixtures/media/zoom-meeting-metadata.sample.json").getInputStream()) {
            Files.copy(in, sidecar);
        }

        MeetingMetadata metadata = reader.read(sidecar);

        assertThat(metadata.meetingId()).isEqualTo("84756123901");
        assertThat(metadata.topic()).isEqualTo("Weekly Sync - Resonance Flow");
        assertThat(metadata.hostName()).isEqualTo("Dana Levi");
        assertThat(metadata.startedAt()).isEqualTo(Instant.parse("2026-09-08T09:00:03Z"));
        assertThat(metadata.endedAt()).isEqualTo(Instant.parse("2026-09-08T09:41:17Z"));
        assertThat(metadata.participants()).hasSize(3);
        assertThat(metadata.participants().get(1).name()).isEqualTo("Esther Ora");
        assertThat(metadata.participants().get(1).joinedAt()).isEqualTo(Instant.parse("2026-09-08T09:01:12Z"));
        assertThat(metadata.participants().get(1).leftAt()).isEqualTo(Instant.parse("2026-09-08T09:41:17Z"));
    }
}
