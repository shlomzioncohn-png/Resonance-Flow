package com.rnd.bridge.crm.mapper;

import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.dto.ExtractionProvenance;
import com.rnd.bridge.dto.RoleContext;
import com.rnd.bridge.dto.SourceType;
import com.rnd.bridge.dto.Task;
import com.rnd.bridge.dto.TaskStatus;
import com.rnd.bridge.dto.Tier;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public final class DealToTaskMapper {

    private static final String SOURCE_REF_PREFIX = "crm:";

    // Deal.closeDate -> Task.deadline convention (end-of-day UTC). Provisional
    // pending confirmation from Inbox re: a shared date-to-instant convention
    // across all adapters - one-line change here once the answer lands.
    private static final ZoneOffset DEADLINE_ZONE = ZoneOffset.UTC;
    private static final LocalTime DEADLINE_TIME_OF_DAY = LocalTime.MAX;

    private DealToTaskMapper() {
    }

    public static TaskStatus toTaskStatus(DealStage stage) {
        return switch (stage) {
            case PROSPECT, QUALIFIED, PROPOSAL, NEGOTIATION -> TaskStatus.NEW;
            // Inbox requires snoozed_until whenever status = SNOOZED, but a
            // Deal carries no resume date (closeDate is a target close date,
            // not a snooze-until date). Open question for Inbox: what
            // populates snoozed_until for an ON_HOLD deal? Not inventing one.
            case ON_HOLD -> TaskStatus.SNOOZED;
            case CLOSED_WON -> TaskStatus.DONE;
            case CLOSED_LOST -> TaskStatus.CANCELLED;
        };
    }

    public static String toSourceRef(Deal deal) {
        return SOURCE_REF_PREFIX + deal.dealId();
    }

    public static Task toTask(Deal deal, Instant asOf) {
        Objects.requireNonNull(deal, "deal must not be null");
        Objects.requireNonNull(asOf, "asOf must not be null");

        return new Task(
                null, // organizationId - no source in Deal; open question for Inbox, not inventing one
                null, // projectId - no source in Deal
                deal.title(),
                deal.description(),
                toTaskStatus(deal.stage()),
                toUrgency(deal, asOf),
                null, // confidence - null on this deterministic ETL lane; Inbox requires confidence only for ML-inferred rows
                Tier.TACTICAL,
                ExtractionProvenance.ETL,
                RoleContext.SALESPERSON,
                SourceType.CRM_DEAL,
                toSourceRef(deal),
                null, // estimatedDurationMinutes - a CRM deal carries no effort signal
                toDeadline(deal.closeDate()),
                null); // snoozedUntil - no source in Deal; open question for Inbox, not inventing one
    }

    /**
     * Urgency for Inbox's {@code tasks.urgency} ({@code SMALLINT CHECK
     * (urgency BETWEEN 1 AND 5)}), built from deal momentum plus a
     * time-pressure bonus for open deals.
     * <p>
     * Base score by stage (deal momentum):
     * <ul>
     *   <li>{@code NEGOTIATION} -&gt; 4</li>
     *   <li>{@code PROPOSAL} -&gt; 3</li>
     *   <li>{@code QUALIFIED} -&gt; 2</li>
     *   <li>{@code PROSPECT}, {@code ON_HOLD}, {@code CLOSED_WON}, {@code
     *       CLOSED_LOST} -&gt; 1</li>
     * </ul>
     * Time-pressure bonus, added only for the four open stages ({@code
     * PROSPECT}, {@code QUALIFIED}, {@code PROPOSAL}, {@code NEGOTIATION}),
     * based on {@code daysToClose} - the day count from {@code asOf}'s UTC
     * date to {@code deal.closeDate()}:
     * <ul>
     *   <li>{@code closeDate} null -&gt; +0</li>
     *   <li>{@code daysToClose < 0} (already overdue, still open) -&gt; +2</li>
     *   <li>{@code 0 <= daysToClose <= 3} -&gt; +2</li>
     *   <li>{@code 4 <= daysToClose <= 14} -&gt; +1</li>
     *   <li>{@code daysToClose > 14} -&gt; +0</li>
     * </ul>
     * {@code ON_HOLD}, {@code CLOSED_WON} and {@code CLOSED_LOST} get no
     * bonus: a paused or finished deal should not be pushed up the queue by
     * a date. The result is clamped to 1..5.
     */
    static Integer toUrgency(Deal deal, Instant asOf) {
        int urgency = switch (deal.stage()) {
            case PROSPECT -> 1 + openStageBonus(deal.closeDate(), asOf);
            case QUALIFIED -> 2 + openStageBonus(deal.closeDate(), asOf);
            case PROPOSAL -> 3 + openStageBonus(deal.closeDate(), asOf);
            case NEGOTIATION -> 4 + openStageBonus(deal.closeDate(), asOf);
            case ON_HOLD -> 1;
            case CLOSED_WON -> 1;
            case CLOSED_LOST -> 1;
        };
        return clamp(urgency, 1, 5);
    }

    private static int openStageBonus(LocalDate closeDate, Instant asOf) {
        if (closeDate == null) {
            return 0;
        }
        long daysToClose = ChronoUnit.DAYS.between(LocalDate.ofInstant(asOf, ZoneOffset.UTC), closeDate);
        if (daysToClose <= 3) {
            return 2;
        }
        if (daysToClose <= 14) {
            return 1;
        }
        return 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Deal.closeDate is a plain date; Inbox's {@code tasks.deadline} is
     * {@code TIMESTAMPTZ}. Converted to end-of-day UTC so a deal closing "on
     * the 30th" is not already overdue at 00:01 on the 30th.
     * <p>
     * Provisional pending confirmation from Inbox: a shared date-to-instant
     * convention across all adapters is out to their team lead. Should be a
     * one-line change ({@link #DEADLINE_ZONE} / {@link #DEADLINE_TIME_OF_DAY})
     * once the answer lands.
     */
    static Instant toDeadline(LocalDate closeDate) {
        if (closeDate == null) {
            return null;
        }
        return closeDate.atTime(DEADLINE_TIME_OF_DAY).toInstant(DEADLINE_ZONE);
    }
}
