package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.lane.CalendarEventStateStore;
import com.rnd.bridge.intake.model.CalendarEventState;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Resolves the correlationId/revision Bridge should use for one Google Calendar event, given
 * its stable event.id and Google's etag for the version currently being read - the Calendar
 * analog of ChatGroupStateReconciler, but far simpler: Calendar's event.id is already a stable,
 * directly-lookupable key on every poll (no thread-continuation/time-gap-grouping problem to
 * solve), so this is a single atomic read-modify-write per event, not a multi-message merge.
 * Called once per event by RealCalendarSourceReader/FixtureCalendarSourceReader while building
 * each event's SourceRecord - see their toSourceRecord.
 * resolve()'s three outcomes, matching CalendarEventState's javadoc:
 * - first-seen eventId: mint a new correlationId, start at revision 0, unconfirmed.
 * - same etag as last time: a retry (of either an unconfirmed or an already-published
 *   revision) - reuse the same correlationId/revision/etag/published state untouched. This is
 *   what stops a publish-failure retry (syncToken frozen, same delta re-read) from incrementing
 *   the revision a second time for content that never actually changed.
 * - different etag: a genuinely newer Calendar version - same correlationId, revision + 1,
 *   published reset to false (a new version is unconfirmed until CalendarEventStateStore.
 *   confirmPublished says otherwise).
 * Runs under CalendarEventStateStore's per-eventId lock (via ControlPlaneStateStore), so two
 * concurrent reads of the same event (e.g. overlapping poll requests) can't race each other's
 * revision bump - and different eventIds never contend, since each uses its own lock key.
 */
@Service
public class CalendarEventStateReconciler {

    private final CalendarEventStateStore store;

    public CalendarEventStateReconciler(CalendarEventStateStore store) {
        this.store = store;
    }

    public CalendarEventState resolve(String tenantId, String eventId, String etag) {
        return store.update(tenantId, eventId, current -> {
            if (current.eventId() == null) {
                // Nothing persisted yet for this eventId - first time Bridge has ever seen it.
                return new CalendarEventState(eventId, tenantId, UUID.randomUUID().toString(), etag, 0, false);
            }
            if (etagUnchanged(current, etag)) {
                // Same version as last time - a retry, not a new revision. Leave everything as-is,
                // including `published`, so an already-confirmed retry stays confirmed and an
                // unconfirmed one stays pending at the SAME revision (see class javadoc).
                return current;
            }
            return new CalendarEventState(
                    eventId, tenantId, current.correlationId(), etag, current.revision() + 1, false);
        });
    }

    private static boolean etagUnchanged(CalendarEventState current, String etag) {
        return current.etag() == null ? etag == null : current.etag().equals(etag);
    }
}
