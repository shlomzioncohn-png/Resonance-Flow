package com.rnd.bridge.media.meeting;

import java.time.Instant;

/**
 * One participant's presence in a meeting, as reported by the source platform's API/export
 * (Zoom Dashboard/Report API, BBB). leftAt is nullable - some sources only report it once the
 * meeting has fully ended, or not at all for a participant still connected when the report was
 * generated.
 * <p>
 * userId, status, duration, failover, customerKey, registrantId, and boMtgId are Zoom-only
 * fields from GET /report/meetings/{meetingId}/participants - BBB has none of these, so they're
 * always null there.
 */
public record ParticipantActivity(String id, String name, Instant joinedAt, Instant leftAt,
                                   String userId, String status, Integer durationSeconds,
                                   Boolean failover, String customerKey, String registrantId,
                                   String boMtgId) {

    public ParticipantActivity(String id, String name, Instant joinedAt, Instant leftAt) {
        this(id, name, joinedAt, leftAt, null, null, null, null, null, null, null);
    }
}
