package com.rnd.bridge.media.meeting.zoom;

import java.util.List;
import java.util.Optional;

/**
 * Fetches a meeting's recording assets from Zoom's real Cloud Recording API
 * ({@code GET /meetings/{meetingId}/recordings}) - the source of the transcript sidecar this
 * lane currently only gets from a manually-placed local {@code .vtt} file (see
 * MediaIntakeOrchestrator's "Transcript pairing" and README.md). {@link #findTranscript} picks
 * out the one {@link ZoomRecordingFile} with {@code fileType == "TRANSCRIPT"}; downloading its
 * {@code downloadUrl} still needs the OAuth access token attached (query param or Bearer
 * header depending on app type) or Zoom 404s it.
 * <p>
 * Not called from anywhere yet - {@link com.rnd.bridge.media.MediaIntakeOrchestrator} is
 * folder-driven and has no {@code meetingId} to call this with (same blocker documented on
 * {@link com.rnd.bridge.media.meeting.FixtureZoomMeetingMetadataReader}). This interface +
 * {@link RealZoomCloudRecordingClient} exist so that wiring a real {@code meetingId} source
 * (e.g. a Zoom webhook, or a future non-fixture trigger) only has to plug into this seam, not
 * design it from scratch.
 */
public interface ZoomCloudRecordingClient {

    /**
     * The full response - every {@link ZoomRecordingFile} (video, audio, chat, transcript,
     * ...) plus meeting-level recording fields, for whatever eventually builds the "additional
     * info" metadata sidecar file. {@link #findTranscript} is a convenience for the one entry
     * most callers actually need.
     *
     * @param meetingId Zoom's meeting UUID (the {@code uuid} field from the Report API, or
     *                   Zoom's webhook payloads - double-URL-encoded if it contains {@code /}
     *                   or starts with {@code //}, per Zoom's own documented gotcha)
     */
    ZoomRecordingsResponse fetchRecordings(String meetingId);

    /** Convenience: the one {@link ZoomRecordingFile} in {@link #fetchRecordings} with {@code fileType == "TRANSCRIPT"}. */
    default Optional<ZoomRecordingFile> findTranscript(String meetingId) {
        List<ZoomRecordingFile> files = fetchRecordings(meetingId).recordingFiles();
        return files == null ? Optional.empty()
                : files.stream().filter(ZoomRecordingFile::isTranscript).findFirst();
    }

    /** Downloads one file's bytes given a {@link ZoomRecordingFile} from {@link #fetchRecordings}. */
    byte[] download(ZoomRecordingFile file);
}
