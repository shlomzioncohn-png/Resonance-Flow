package com.rnd.bridge.media.meeting.zoom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Placeholder for the real Zoom Cloud Recording API client - same pattern as
 * {@code RealSlackSourceReader}/{@code RealEmailSourceReader}: not implemented yet, enabling
 * {@code bridge.media.zoom.mode=real} activates this bean but every call fails until it's
 * filled in. {@link ZoomCloudRecordingClient}, {@link ZoomRecordingFile} and
 * {@link ZoomRecordingsResponse} are safe to keep as-is - they only describe the documented
 * response shape, nothing that can silently break by never having been run. The actual HTTP
 * call is deliberately NOT written speculatively, per the same reasoning the other Real*
 * connectors in this repo follow: untested network/auth code that nothing ever exercises is a
 * false sense of "ready" - a wrong header or field would only surface the first time someone
 * with real Zoom credentials runs it, with no earlier signal.
 * <p>
 * When there's a meetingId source and real Server-to-Server OAuth credentials to test against,
 * implementing this means: (1) exchange accountId/clientId/clientSecret for a token via
 * {@code POST https://zoom.us/oauth/token?grant_type=account_credentials&account_id=...} with
 * HTTP Basic auth, caching it until shortly before its ~1 hour expiry; (2) call
 * {@code GET https://api.zoom.us/v2/meetings/{meetingId}/recordings} with that token as a
 * Bearer header, deserializing into {@link ZoomRecordingsResponse}; (3) for
 * {@link #download}, GET the file's own {@code downloadUrl} (a different host - zoom.us, not
 * api.zoom.us) with the same Bearer token attached, or Zoom 404s it. Not called from anywhere
 * yet either way - {@link com.rnd.bridge.media.MediaIntakeOrchestrator} is folder-driven and
 * has no {@code meetingId} (same blocker as
 * {@link com.rnd.bridge.media.meeting.FixtureZoomMeetingMetadataReader}).
 */
@Component
@ConditionalOnProperty(prefix = "bridge.media.zoom", name = "mode", havingValue = "real")
public class RealZoomCloudRecordingClient implements ZoomCloudRecordingClient {

    @Override
    public ZoomRecordingsResponse fetchRecordings(String meetingId) {
        throw new UnsupportedOperationException(
                "Real Zoom Cloud Recording API access is not implemented yet - see this class's javadoc");
    }

    @Override
    public byte[] download(ZoomRecordingFile file) {
        throw new UnsupportedOperationException(
                "Real Zoom Cloud Recording API access is not implemented yet - see this class's javadoc");
    }
}
