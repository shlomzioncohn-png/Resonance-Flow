package com.rnd.bridge.media.meeting;

import java.time.Instant;

/**
 * One participant's presence in a meeting, as reported by the source platform's API/export
 * (Zoom Dashboard/Report API, BBB). leftAt is nullable - some sources only report it once the
 * meeting has fully ended, or not at all for a participant still connected when the report was
 * generated.
 */
public record ParticipantActivity(String id, String name, Instant joinedAt, Instant leftAt) {
}
