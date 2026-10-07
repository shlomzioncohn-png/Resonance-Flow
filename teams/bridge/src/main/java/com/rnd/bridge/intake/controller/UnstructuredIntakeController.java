package com.rnd.bridge.intake.controller;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.KeyedLock;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.intake.lane.CursorStore;
import com.rnd.bridge.intake.lane.UnstructuredIntakeLane;
import com.rnd.bridge.intake.parser.SourceRecordGrouper;
import com.rnd.bridge.intake.parser.calendar.CalendarReadResult;
import com.rnd.bridge.intake.parser.calendar.CalendarSourceReader;
import com.rnd.bridge.intake.parser.email.EmailReadResult;
import com.rnd.bridge.intake.parser.email.EmailSourceReader;
import com.rnd.bridge.raw.model.RawSourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bridge/intake")
public class UnstructuredIntakeController {

    // Single-tenant today, like every other default in this lane (WhatsAppImportProperties,
    // SlackApiProperties, ScopingProperties) - revisit if/when this lane serves more than one
    // tenant.
    private static final String TENANT_ID = "kamatech";

    private final Map<RawSourceType, UnstructuredIntakeLane> lanesBySource;
    private final CursorStore cursorStore;
    private final CalendarSourceReader calendarSourceReader;
    private final EmailSourceReader emailSourceReader;
    private final KeyedLock calendarCursorLock;

    public UnstructuredIntakeController(
            List<UnstructuredIntakeLane> lanes, CursorStore cursorStore, CalendarSourceReader calendarSourceReader,
            EmailSourceReader emailSourceReader, KeyedLock calendarCursorLock) {
        this.lanesBySource = lanes.stream()
                .collect(Collectors.toMap(UnstructuredIntakeLane::sourceType, Function.identity()));
        this.cursorStore = cursorStore;
        this.calendarSourceReader = calendarSourceReader;
        this.emailSourceReader = emailSourceReader;
        // Same KeyedLock instance as Calendar's - it's a per-key lock, and the key already
        // includes the sourceType (see calendarCursorLockKey/emailCursorLockKey), so one shared
        // bean safely serializes both sources' independent load-read-process-save cycles without
        // them ever contending on each other's key.
        this.calendarCursorLock = calendarCursorLock;
    }

    @PostMapping("/{sourceType}/run")
    public IntakeResult run(@PathVariable RawSourceType sourceType) {
        UnstructuredIntakeLane lane = lanesBySource.get(sourceType);
        if (lane == null) {
            throw new IllegalArgumentException(sourceType + " is not handled by this lane");
        }
        if (sourceType == RawSourceType.CALENDAR) {
            return runCalendar(lane);
        }
        if (sourceType == RawSourceType.EMAIL) {
            return runEmail(lane);
        }

        ReadCursor cursor = cursorStore.load(sourceType, TENANT_ID);
        Instant asOf = Instant.now();
        IntakeResult result = lane.run(cursor, asOf);
        // Only reached if run() returned normally (no uncaught exception) - see plan.md "Cursor
        // persistence": update the cursor only after the run has actually gone through, never
        // before. A few rejected records within an otherwise-successful run still advance the
        // cursor for every source except Slack; RawObjectEvent.dedupKey() makes any later
        // reprocessing harmless.
        // Slack is the one exception here: its chat groups have their own content-aware publish
        // confirmation (see ChatGroupStateReconciler/GroupStateStore/UnstructuredIntakeLane's
        // afterPublish hook), which makes replaying the same window both safe (an already-
        // confirmed, unchanged group is a cheap no-op) and necessary (an unconfirmed one must
        // be retried, or it would never be revisited once `since` moves past it - a Slack
        // reply/edit is the only thing that would otherwise perturb that group again). No
        // other source has this confirmation mechanism, so freezing their cursor the same way
        // would just force a full, unconditional re-publish of the whole window on every retry.
        if (sourceType != RawSourceType.SLACK || result.rejected() == 0) {
            cursorStore.save(sourceType, TENANT_ID, new ReadCursor(asOf));
        }
        return result;
    }

    /**
     * Calendar's cursor is a Google syncToken, not a timestamp - Google's contract is that
     * presenting a syncToken means "only what changed since the sync that produced it", so
     * advancing to a new token before every record from the read that produced it was actually
     * published would permanently lose whatever failed (a later poll would never see it again).
     * Calendar therefore takes a different path through this controller than every other
     * source: it reads via calendarSourceReader.read(cursor) directly (one single call, exactly
     * as many events.list round trips as before), not through lane.run(ReadCursor, Instant) -
     * so the nextSyncToken that call produces comes back as part of the SAME CalendarReadResult
     * as the records themselves, tied to this one invocation, never to shared reader state that
     * a second concurrent call could overwrite first (see CalendarReadResult/
     * RealCalendarSourceReader's javadoc). The already-fetched records are then run through the
     * lane's normal map/validate/publish loop via the Stream overload of run(). The persistence
     * rule is otherwise identical to Slack's freeze: only advance on a fully successful run
     * (rejected == 0); Calendar has no equivalent of Slack's own unconfirmed-group retry path,
     * so on a partial failure it simply keeps polling from the same syncToken next run.
     * <p>
     * The whole load-read-process-save sequence runs under {@link #calendarCursorLock}, keyed by
     * (CALENDAR, tenantId): without it, two overlapping runs could both load the same stored
     * token, each fetch their own next token from Google, and then race on cursorStore.save -
     * the loser's save silently regresses the cursor back behind the winner's, even though the
     * winner already published everything up to its own token. Holding the lock for the full
     * transaction (not just around save, and not around load/save separately) is what actually
     * prevents that: a second run's load() cannot happen until the first run has saved and
     * released, so it always starts from the first run's result rather than its stale input.
     */
    private IntakeResult runCalendar(UnstructuredIntakeLane lane) {
        AtomicReference<IntakeResult> result = new AtomicReference<>();
        calendarCursorLock.run(calendarCursorLockKey(TENANT_ID), () -> result.set(runCalendarLocked(lane)));
        return result.get();
    }

    private IntakeResult runCalendarLocked(UnstructuredIntakeLane lane) {
        ReadCursor cursor = cursorStore.load(RawSourceType.CALENDAR, TENANT_ID);
        Instant asOf = Instant.now();
        CalendarReadResult calendarRead = calendarSourceReader.read(cursor);
        IntakeResult result = lane.run(SourceRecordGrouper.groupByCorrelationId(calendarRead.records()).stream(), asOf);
        if (result.rejected() == 0) {
            String nextSyncToken = calendarRead.nextSyncToken();
            // Null means this read never produced a new cursor to advance to (e.g. fixture
            // mode) - leave whatever's already stored untouched rather than overwriting a real
            // token with nothing.
            if (nextSyncToken != null) {
                cursorStore.save(RawSourceType.CALENDAR, TENANT_ID, new ReadCursor(asOf, nextSyncToken));
            }
        }
        return result;
    }

    private static String calendarCursorLockKey(String tenantId) {
        return "%s/%s".formatted(RawSourceType.CALENDAR.name(), tenantId);
    }

    /**
     * Gmail's cursor is a historyId, not a timestamp - same reasoning as Calendar's syncToken
     * (see runCalendar/runCalendarLocked's javadoc): advancing to a new historyId before every
     * record from the read that produced it was actually published would permanently lose
     * whatever failed, since Gmail's history API only ever returns what changed since a given
     * id, not a replay of everything again. Email therefore takes the same dedicated path as
     * Calendar - reads via emailSourceReader.read(cursor) directly, with the resulting
     * nextHistoryId tied to that one call (see EmailReadResult/RealEmailSourceReader's javadoc),
     * then runs the already-fetched records through the lane's normal map/validate/publish loop
     * via the Stream overload of run(). Persistence rule is identical to Calendar's: only
     * advance on a fully successful run (rejected == 0).
     * Shares calendarCursorLock (not a separate lock) - see the constructor's comment for why
     * that's safe: the lock key already encodes the sourceType, so Email and Calendar never
     * contend on each other's key despite using the same KeyedLock instance.
     */
    private IntakeResult runEmail(UnstructuredIntakeLane lane) {
        AtomicReference<IntakeResult> result = new AtomicReference<>();
        calendarCursorLock.run(emailCursorLockKey(TENANT_ID), () -> result.set(runEmailLocked(lane)));
        return result.get();
    }

    private IntakeResult runEmailLocked(UnstructuredIntakeLane lane) {
        ReadCursor cursor = cursorStore.load(RawSourceType.EMAIL, TENANT_ID);
        Instant asOf = Instant.now();
        EmailReadResult emailRead = emailSourceReader.read(cursor);
        IntakeResult result = lane.run(SourceRecordGrouper.groupByCorrelationId(emailRead.records()).stream(), asOf);
        if (result.rejected() == 0) {
            String nextHistoryId = emailRead.nextHistoryId();
            // Null means this read never produced a new cursor to advance to (e.g. fixture
            // mode) - leave whatever's already stored untouched rather than overwriting a real
            // historyId with nothing.
            if (nextHistoryId != null) {
                cursorStore.save(RawSourceType.EMAIL, TENANT_ID, new ReadCursor(asOf, null, nextHistoryId));
            }
        }
        return result;
    }

    private static String emailCursorLockKey(String tenantId) {
        return "%s/%s".formatted(RawSourceType.EMAIL.name(), tenantId);
    }
}
