package com.rnd.bridge.media.meeting;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Meeting-level details plus the participant list, parsed from a source platform's
 * meeting-metadata sidecar (Zoom's report-API-shaped JSON, or BBB's metadata.xml) - see
 * MeetingMetadataReader. Only used to derive RawObjectEvent.participants (names) here in
 * Bridge; the sidecar file itself is landed as-is in its native format, untouched by this
 * model - Bridge doesn't interpret meeting content beyond that one flat name list.
 * <p>
 * hostEmail, not hostName: Zoom's Report API (GET /report/meetings/{meetingId}) has no
 * display-name field for the meeting's host - only a user_email field. A display name would
 * need a separate Users API call this lane doesn't make. BBB has neither, so it's always
 * null there.
 * <p>
 * uuid, dept, durationMinutes, totalMinutes, participantsCount, type, customKeys, and
 * trackingFields are Zoom-only fields from GET /report/meetings/{meetingId} - BBB has none
 * of these, so they're always null there. recordId is BBB-only (getRecordings' recordID) -
 * always null for Zoom.
 */
public record MeetingMetadata(String meetingId, String topic, String hostEmail, Instant startedAt,
                               Instant endedAt, List<ParticipantActivity> participants,
                               String uuid, String dept, Integer durationMinutes,
                               Integer totalMinutes, Integer participantsCount, Integer type,
                               List<CustomField> customKeys, List<CustomField> trackingFields,
                               String recordId) {

    public MeetingMetadata(String meetingId, String topic, String hostEmail, Instant startedAt,
                            Instant endedAt, List<ParticipantActivity> participants) {
        this(meetingId, topic, hostEmail, startedAt, endedAt, participants,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * The platform's own identifier for this one meeting occurrence, for RawObjectEvent.source_ref
     * - Zoom's meeting uuid, or BBB's recordID; null when the sidecar has neither. Never meetingId:
     * both platforms reuse it (a recurring Zoom meeting, a BBB room) across occurrences, and the
     * #31 contract requires source_ref to be unique within its source_type.
     */
    public String sourceRef() {
        return uuid != null ? uuid : recordId;
    }

    /**
     * Distinct people, not join sessions: Zoom lists a participant once per join, so someone who
     * dropped and rejoined appears twice. Falls back to the platform's own count, and is empty when
     * neither is known (BBB's sidecar has no participant data) - callers must never read empty as one.
     */
    public Optional<Integer> distinctParticipantCount() {
        if (participants != null && !participants.isEmpty()) {
            return Optional.of((int) participants.stream()
                    .map(MeetingMetadata::identityOf)
                    .distinct()
                    .count());
        }
        return Optional.ofNullable(participantsCount);
    }

    private static String identityOf(ParticipantActivity participant) {
        if (participant.userId() != null) {
            return "user:" + participant.userId();
        }
        if (participant.id() != null) {
            return "id:" + participant.id();
        }
        return "name:" + participant.name();
    }

    /** A single key/value pair - shared shape for Zoom's custom_keys and tracking_fields arrays. */
    public record CustomField(String key, String value) {
    }
}
