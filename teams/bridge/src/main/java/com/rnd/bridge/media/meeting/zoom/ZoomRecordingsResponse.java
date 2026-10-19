package com.rnd.bridge.media.meeting.zoom;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/**
 * Zoom Cloud Recording API's {@code GET /meetings/{meetingId}/recordings} response - lists
 * every recording asset for one meeting plus meeting-level recording metadata (distinct from,
 * and complementary to, the Report API fields already captured by
 * {@link com.rnd.bridge.media.meeting.MeetingMetadata} - {@code accountId}/{@code hostId} here
 * are ids, not the Report API's {@code hostEmail}). All fields kept, not just the ones
 * {@link ZoomCloudRecordingClient#findTranscript} needs, since a future caller building the
 * "additional info" sidecar file wants everything this endpoint has, not a pre-filtered subset.
 * See {@link ZoomCloudRecordingClient}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ZoomRecordingsResponse(String uuid, Long id, String accountId, String hostId, String topic,
                                      Instant startTime, String timezone, Integer duration, Long totalSize,
                                      Integer recordingCount, String shareUrl, String password,
                                      String recordingPlayPasscode, Instant autoDeleteDate,
                                      List<ZoomRecordingFile> recordingFiles) {
}
