package com.rnd.bridge.crm.mapper;

import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.task.AbstractTaskMapper;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.TaskEnricher;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Maps a CRM {@link Deal} to the canonical {@code Task} (stage to status, close date to deadline, urgency).
 * {@code organizationId} is one configured value standing in for a real per-deal tenant lookup (not implemented).
 */
public final class DealToTaskMapper extends AbstractTaskMapper<Deal> {

    private final String organizationId;
    private final int stalledAfterDays;

    public DealToTaskMapper(String organizationId,
                            EnrichmentProperties enrichmentProperties,
                            TaskEnricher taskEnricher,
                            int stalledAfterDays) {
        super(enrichmentProperties, taskEnricher);
        this.organizationId = organizationId;
        this.stalledAfterDays = stalledAfterDays;
    }

    // Not LocalTime.MAX: Postgres TIMESTAMPTZ rounds .999999999 up to the next second, landing a day late.
    private static final ZoneOffset DEADLINE_ZONE = ZoneOffset.UTC;
    private static final LocalTime DEADLINE_TIME_OF_DAY = LocalTime.of(23, 59, 59);

    public static TaskStatus toTaskStatus(DealStage stage) {
        return switch (stage) {
            case PROSPECT, QUALIFIED, PROPOSAL, NEGOTIATION -> TaskStatus.NEW;
            // Not a paused/snoozed status - a Deal has no resume date, so ON_HOLD stays NEW.
            case ON_HOLD -> TaskStatus.NEW;
            case CLOSED_WON -> TaskStatus.DONE;
            case CLOSED_LOST -> TaskStatus.CANCELLED;
        };
    }

    static Instant toDeadline(LocalDate closeDate) {
        if (closeDate == null) {
            return null;
        }
        return closeDate.atTime(DEADLINE_TIME_OF_DAY).toInstant(DEADLINE_ZONE);
    }

    @Override
    protected String organizationId() {
        return organizationId;
    }

    @Override
    protected SourceType sourceType() {
        return SourceType.CRM_DEAL;
    }

    @Override
    protected Tier tier() {
        return Tier.TACTICAL;
    }

    @Override
    protected RoleContext roleContext() {
        return RoleContext.SALESPERSON;
    }

    @Override
    protected String toTitle(Deal deal) {
        return deal.title();
    }

    @Override
    protected String toDescription(Deal deal) {
        return deal.description();
    }

    @Override
    public String toSourceRef(Deal deal) {
        return deal.sourceRefPrefix() + deal.dealId();
    }

    @Override
    protected TaskStatus toStatus(Deal deal) {
        return toTaskStatus(deal.stage());
    }

    /**
     * Urgency: stage base (PROSPECT 1 / QUALIFIED 2 / PROPOSAL 3 / NEGOTIATION 4, others 1) + close-date
     * bonus (open stages only: +2 if &lt;=3 days to close, +1 if &lt;=14, else +0) + stalled +1; clamped to 1..5.
     */
    @Override
    protected Integer toUrgency(Deal deal, Instant asOf) {
        int urgency = switch (deal.stage()) {
            case PROSPECT -> 1 + openStageBonus(deal.closeDate(), asOf);
            case QUALIFIED -> 2 + openStageBonus(deal.closeDate(), asOf);
            case PROPOSAL -> 3 + openStageBonus(deal.closeDate(), asOf);
            case NEGOTIATION -> 4 + openStageBonus(deal.closeDate(), asOf);
            case ON_HOLD -> 1;
            case CLOSED_WON -> 1;
            case CLOSED_LOST -> 1;
        };
        if (isStalled(deal, asOf)) {
            urgency += 1;
        }
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

    private boolean isStalled(Deal deal, Instant asOf) {
        if (stalledAfterDays <= 0 || !isOpenStage(deal.stage())) {
            return false;
        }
        long daysSinceStageChange = ChronoUnit.DAYS.between(
                LocalDate.ofInstant(deal.stageChangedAt(), ZoneOffset.UTC),
                LocalDate.ofInstant(asOf, ZoneOffset.UTC));
        return daysSinceStageChange >= stalledAfterDays;
    }

    private static boolean isOpenStage(DealStage stage) {
        return switch (stage) {
            case PROSPECT, QUALIFIED, PROPOSAL, NEGOTIATION -> true;
            case ON_HOLD, CLOSED_WON, CLOSED_LOST -> false;
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected Instant toDeadline(Deal deal) {
        return toDeadline(deal.closeDate());
    }



    @Override
    protected Instant toCompletedAt(Deal deal) {
        // Only CLOSED_WON completed; reuses toDeadline so both conversions can't drift (D-10552: null close date -> null).
        return deal.stage() == DealStage.CLOSED_WON ? toDeadline(deal.closeDate()) : null;
    }
}
