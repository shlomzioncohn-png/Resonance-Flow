package com.rnd.bridge.media.meeting.zoom;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

/**
 * One entry of Zoom Cloud Recording API's {@code recording_files} array (see
 * {@link ZoomCloudRecordingClient}) - field names/structure match that response exactly
 * (verified against public Zoom API docs, not live this session - see
 * fixtures/media/zoom-cloud-recording.sample.json for the full caveat). A meeting has one of
 * these per asset (video, audio, chat, transcript); the transcript is the entry where
 * {@code fileType} is {@code TRANSCRIPT}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ZoomRecordingFile(String id, String meetingId, Instant recordingStart, Instant recordingEnd,
                                 String fileType, String fileExtension, Long fileSize, String playUrl,
                                 String downloadUrl, String status, String recordingType) {

    private static final String TRANSCRIPT_FILE_TYPE = "TRANSCRIPT";

    public boolean isTranscript() {
        return TRANSCRIPT_FILE_TYPE.equals(fileType);
    }
}
