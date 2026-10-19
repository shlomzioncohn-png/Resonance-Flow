package com.rnd.bridge.media.classification;

import com.rnd.bridge.media.meeting.MeetingMetadata;

/**
 * What a meeting is classified from - its platform metadata and its transcript, either of which
 * may be null (no sidecar, or it didn't parse). The recording's audio is never sent anywhere.
 */
public record MeetingClassificationInput(MeetingMetadata metadata, String transcript) {
}
