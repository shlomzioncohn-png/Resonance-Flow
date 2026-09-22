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

        assertThat(metadata.meetingId()).isEqualTo("3927350525");
        assertThat(metadata.topic()).isEqualTo("Weekly Sync - Resonance Flow");
        assertThat(metadata.hostEmail()).isEqualTo("dana.levi@example.com");
        assertThat(metadata.startedAt()).isEqualTo(Instant.parse("2026-09-08T09:00:03Z"));
        assertThat(metadata.endedAt()).isEqualTo(Instant.parse("2026-09-08T09:41:17Z"));
        assertThat(metadata.participants()).hasSize(3);
        assertThat(metadata.participants().get(1).name()).isEqualTo("Esther Ora");
        assertThat(metadata.participants().get(1).joinedAt()).isEqualTo(Instant.parse("2026-09-08T09:01:12Z"));
        assertThat(metadata.participants().get(1).leftAt()).isEqualTo(Instant.parse("2026-09-08T09:41:17Z"));
    }

    @Test
    void malformedTimestampDegradesToNullInsteadOfLosingParticipants() throws Exception {
        Path sidecar = tempDir.resolve("meeting.meeting.json");
        Files.writeString(sidecar, """
                {"id": "1", "topic": "Sync", "user_email": "dana@example.com",
                 "start_time": "not-a-real-timestamp", "end_time": "2026-09-08T09:30:00Z",
                 "participants": [
                   {"id": "a", "name": "Dana Levi", "join_time": "2026-09-08T09:00:00Z", "leave_time": "2026-09-08T09:30:00Z"}
                 ]}""");

        MeetingMetadata metadata = reader.read(sidecar);

        assertThat(metadata.startedAt()).isNull();
        assertThat(metadata.endedAt()).isEqualTo(Instant.parse("2026-09-08T09:30:00Z"));
        assertThat(metadata.participants()).hasSize(1);
        assertThat(metadata.participants().get(0).name()).isEqualTo("Dana Levi");
    }

    @Test
    void oneParticipantsUnparsableFieldDegradesToNullNotTheWholeList() throws Exception {
        Path sidecar = tempDir.resolve("meeting.meeting.json");
        Files.writeString(sidecar, """
                {"id": "1", "topic": "Sync", "user_email": "dana@example.com",
                 "start_time": "2026-09-08T09:00:00Z", "end_time": "2026-09-08T09:30:00Z",
                 "participants": [
                   {"id": "a", "name": "Dana Levi", "join_time": "not-a-real-timestamp", "leave_time": "2026-09-08T09:30:00Z"},
                   {"id": "b", "name": "Esther Ora", "join_time": "2026-09-08T09:01:00Z", "leave_time": "2026-09-08T09:30:00Z"}
                 ]}""");

        MeetingMetadata metadata = reader.read(sidecar);

        assertThat(metadata.startedAt()).isEqualTo(Instant.parse("2026-09-08T09:00:00Z"));
        assertThat(metadata.participants()).hasSize(2);
        assertThat(metadata.participants().get(0).joinedAt()).isNull();
        assertThat(metadata.participants().get(1).name()).isEqualTo("Esther Ora");
    }
}
