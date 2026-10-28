package com.rnd.bridge.intake.controller;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.KeyedLock;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.intake.config.BridgeTenantId;
import com.rnd.bridge.intake.lane.CursorStore;
import com.rnd.bridge.intake.lane.UnstructuredIntakeLane;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.parser.SourceRecordGrouper;
import com.rnd.bridge.intake.parser.calendar.CalendarReadResult;
import com.rnd.bridge.intake.parser.calendar.CalendarSourceReader;
import com.rnd.bridge.intake.parser.drive.DriveReadResult;
import com.rnd.bridge.intake.parser.drive.DriveSourceReader;
import com.rnd.bridge.intake.parser.email.EmailReadResult;
import com.rnd.bridge.intake.parser.email.EmailSourceReader;
import com.rnd.bridge.raw.model.RawSourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bridge/intake")
public class UnstructuredIntakeController {

    private static final Logger log = LoggerFactory.getLogger(UnstructuredIntakeController.class);

    // Single-tenant today: bridge.tenant-id, the same value every reader in this lane sends -
    // revisit if/when this lane serves more than one tenant.
    private final String tenantId;

    private final Map<RawSourceType, UnstructuredIntakeLane> lanesBySource;
    private final CursorStore cursorStore;
    private final CalendarSourceReader calendarSourceReader;
    private final EmailSourceReader emailSourceReader;
    private final DriveSourceReader driveSourceReader;
    private final KeyedLock tokenCursorLock;

    public UnstructuredIntakeController(
            List<UnstructuredIntakeLane> lanes, CursorStore cursorStore, CalendarSourceReader calendarSourceReader,
            EmailSourceReader emailSourceReader, DriveSourceReader driveSourceReader, KeyedLock tokenCursorLock,
            BridgeTenantId tenantId) {
        this.tenantId = tenantId.value();
        this.lanesBySource = lanes.stream()
                .collect(Collectors.toMap(UnstructuredIntakeLane::sourceType, Function.identity()));
        this.cursorStore = cursorStore;
        this.calendarSourceReader = calendarSourceReader;
        this.emailSourceReader = emailSourceReader;
        this.driveSourceReader = driveSourceReader;
        // One shared KeyedLock instance for every token-cursor source (Calendar, Email, and any
        // future one) - it's a per-key lock, and the key already includes the sourceType (see
        // cursorLockKey), so this one bean safely serializes each source's independent
        // load-read-process-save cycle without them ever contending on each other's key.
        this.tokenCursorLock = tokenCursorLock;
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
        if (sourceType == RawSourceType.DRIVE) {
            return runDrive(lane);
        }

        ReadCursor cursor = cursorStore.load(sourceType, tenantId);
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
            cursorStore.save(sourceType, tenantId, new ReadCursor(asOf));
        }
        return result;
    }

    /**
     * Calendar's cursor is a Google syncToken, not a timestamp - presenting one means "only what
     * changed since the sync that produced it", tied to the single calendarSourceReader.read(
     * cursor) call that returned it (see CalendarReadResult/RealCalendarSourceReader's javadoc).
     * See {@link #runTokenBasedSource} for the shared persistence/locking rules this and
     * {@link #runEmail} both follow.
     */
    private IntakeResult runCalendar(UnstructuredIntakeLane lane) {
        return runTokenBasedSource(RawSourceType.CALENDAR, cursorLockKey(RawSourceType.CALENDAR, tenantId), lane,
                cursor -> {
                    CalendarReadResult read = calendarSourceReader.read(cursor);
                    return new TokenRead(read.records(), read.nextSyncToken());
                },
                ReadCursor::new);
    }

    /**
     * Drive's cursor is a changes.list startPageToken, not a timestamp - same reasoning as
     * Calendar's syncToken (see {@link #runCalendar}'s javadoc), tied to the single
     * driveSourceReader.read(cursor) call that returned it (see DriveReadResult/
     * RealDriveSourceReader's javadoc). Shares tokenCursorLock with Calendar/Email - safe for
     * the same reason given in the constructor's comment.
     */
    private IntakeResult runDrive(UnstructuredIntakeLane lane) {
        return runTokenBasedSource(RawSourceType.DRIVE, cursorLockKey(RawSourceType.DRIVE, tenantId), lane,
                cursor -> {
                    DriveReadResult read = driveSourceReader.read(cursor);
                    return new TokenRead(read.records(), read.nextToken());
                },
                ReadCursor::new);
    }

    /**
     * Gmail's cursor is a historyId, not a timestamp - same reasoning as Calendar's syncToken
     * (see {@link #runCalendar}'s javadoc), tied to the single emailSourceReader.read(cursor)
     * call that returned it (see EmailReadResult/RealEmailSourceReader's javadoc). Shares
     * tokenCursorLock with Calendar (not a separate lock) - see the constructor's comment for why
     * that's safe: the lock key already encodes the sourceType, so Email and Calendar never
     * contend on each other's key despite using the same KeyedLock instance.
     */
    private IntakeResult runEmail(UnstructuredIntakeLane lane) {
        return runTokenBasedSource(RawSourceType.EMAIL, cursorLockKey(RawSourceType.EMAIL, tenantId), lane,
                cursor -> {
                    EmailReadResult read = emailSourceReader.read(cursor);
                    return new TokenRead(read.records(), read.nextHistoryId());
                },
                ReadCursor::new);
    }

    /**
     * Shared skeleton for every token-cursor source (Calendar, Email, and any future one): load
     * the stored cursor, read under that cursor, run the already-fetched records through the
     * lane's normal map/validate/publish loop, and only advance the stored cursor to the token
     * this read produced if the run was fully successful (rejected == 0) - a partial failure
     * simply keeps polling from the same token next run, since the cursor API contract for both
     * Calendar's syncToken and Email's historyId is "only what changed since this token", so
     * advancing past a token before everything it produced was actually published would
     * permanently lose whatever failed.
     * <p>
     * {@code reader} is applied inside {@code lock}, not before it - the whole
     * load-read-process-save sequence must run as one critical section (see
     * {@link #tokenCursorLock}'s constructor comment), otherwise two overlapping runs could
     * both load the same stored cursor and race on which one's save wins.
     */
    private IntakeResult runTokenBasedSource(
            RawSourceType sourceType, String lockKey, UnstructuredIntakeLane lane,
            Function<ReadCursor, TokenRead> reader, BiFunction<Instant, String, ReadCursor> buildCursor) {
        AtomicReference<IntakeResult> result = new AtomicReference<>();
        tokenCursorLock.run(lockKey, () -> {
            ReadCursor cursor = cursorStore.load(sourceType, tenantId);
            Instant asOf = Instant.now();
            TokenRead read = reader.apply(cursor);
            IntakeResult r = lane.run(SourceRecordGrouper.groupByCorrelationId(read.records()).stream(), asOf);
            // Null token means this read never produced a new cursor to advance to (e.g. fixture
            // mode) - leave whatever's already stored untouched rather than overwriting a real
            // token with nothing.
            if (r.rejected() == 0 && read.token() != null) {
                cursorStore.save(sourceType, tenantId, buildCursor.apply(asOf, read.token()));
                log.info("{} cursor saved: tokenPresent=yes, rejected={}", sourceType, r.rejected());
            } else {
                log.info("{} cursor save skipped: rejected={}, tokenPresent={}",
                        sourceType, r.rejected(), read.token() != null ? "yes" : "no");
            }
            result.set(r);
        });
        return result.get();
    }

    private record TokenRead(List<SourceRecord> records, String token) {}

    private static String cursorLockKey(RawSourceType sourceType, String tenantId) {
        return "%s/%s".formatted(sourceType.name(), tenantId);
    }
}
