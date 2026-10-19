package com.rnd.bridge.media.meeting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MeetingMetadataTest {

    @Test
    void sourceRefIsZoomsUuid() {
        assertThat(metadata("123", "zoom-uuid", null).sourceRef()).isEqualTo("zoom-uuid");
    }

    @Test
    void sourceRefIsBbbsRecordId() {
        assertThat(metadata("room-1", null, "record-1").sourceRef()).isEqualTo("record-1");
    }

    @Test
    void sourceRefNeverFallsBackToTheReusableMeetingId() {
        assertThat(metadata("123", null, null).sourceRef()).isNull();
    }

    private static MeetingMetadata metadata(String meetingId, String uuid, String recordId) {
        return new MeetingMetadata(meetingId, "topic", null, null, null, List.of(),
                uuid, null, null, null, null, null, null, null, recordId);
    }
}
