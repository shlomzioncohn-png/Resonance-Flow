package com.rnd.bridge.media.meeting;

import java.time.Instant;
import java.util.List;

/**
 * Meeting-level details plus the participant list, parsed from a source platform's
 * meeting-metadata sidecar (Zoom's report-API-shaped JSON, or BBB's metadata.xml) - see
 * MeetingMetadataReader. Only used to derive RawObjectEvent.participants (names) here in
 * Bridge; the sidecar file itself is landed as-is in its native format, untouched by this
 * model - Bridge doesn't interpret meeting content beyond that one flat name list.
 */
public record MeetingMetadata(String meetingId, String topic, String hostName, Instant startedAt,
                               Instant endedAt, List<ParticipantActivity> participants) {
}
