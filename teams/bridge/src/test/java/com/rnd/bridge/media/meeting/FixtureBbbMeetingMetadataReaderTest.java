package com.rnd.bridge.media.meeting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class FixtureBbbMeetingMetadataReaderTest {

    @TempDir
    Path tempDir;

    private final FixtureBbbMeetingMetadataReader reader = new FixtureBbbMeetingMetadataReader();

    @Test
    void parsesTheBbbSampleFixture() throws Exception {
        Path sidecar = tempDir.resolve("meeting.metadata.xml");
        try (InputStream in = new ClassPathResource("fixtures/media/bbb-metadata.sample.xml").getInputStream()) {
            Files.copy(in, sidecar);
        }

        MeetingMetadata metadata = reader.read(sidecar);

        assertThat(metadata.meetingId()).isEqualTo("c1a2b3d4-5e6f-7890-abcd-ef1234567890");
        assertThat(metadata.topic()).isEqualTo("Weekly Sync - Resonance Flow");
        assertThat(metadata.startedAt()).isEqualTo(Instant.ofEpochMilli(1757321403000L));
        assertThat(metadata.endedAt()).isEqualTo(Instant.ofEpochMilli(1757323677000L));
        // metadata.xml has no per-participant join/leave data - see class Javadoc.
        assertThat(metadata.participants()).isEmpty();
    }
}
