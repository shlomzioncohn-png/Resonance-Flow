package com.rnd.bridge.media.meeting;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Parses a meeting-metadata sidecar file into MeetingMetadata - Zoom and BBB share this one
 * interface since they produce the same output shape, they just parse a different input format
 * (JSON vs. XML). This is only used to derive RawObjectEvent.participants (see
 * MediaIntakeOrchestrator); the sidecar's raw bytes are landed separately, unparsed and
 * unconverted, via MediaIntakeService.landMeetingMetadata.
 */
public interface MeetingMetadataReader {

    MeetingMetadata read(Path sidecarFile) throws IOException;
}
