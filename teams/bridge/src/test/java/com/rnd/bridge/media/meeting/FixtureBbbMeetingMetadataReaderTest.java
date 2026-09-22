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

        assertThat(metadata.meetingId()).isEqualTo("c637ba21adcd0191f48f5c4bf23fab0f96ed5c18");
        assertThat(metadata.topic()).isEqualTo("Fred's Room");
        assertThat(metadata.startedAt()).isEqualTo(Instant.ofEpochMilli(1530718721124L));
        assertThat(metadata.endedAt()).isEqualTo(Instant.ofEpochMilli(1530718810456L));
        // getRecordings only gives a participant COUNT, not per-person data - see class Javadoc.
        assertThat(metadata.participants()).isEmpty();
    }
}
