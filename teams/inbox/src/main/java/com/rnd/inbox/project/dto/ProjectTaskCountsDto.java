package com.rnd.inbox.project.dto;

public final class ProjectTaskCountsDto {

    private final long total;
    private final long open;
    private final long inProgress;
    private final long snoozed;
    private final long done;
    private final long closedWithoutDone;
    private final long overdue;

    public ProjectTaskCountsDto(long total, long open, long inProgress, long snoozed, long done,
                                long closedWithoutDone, long overdue) {
        this.total = total;
        this.open = open;
        this.inProgress = inProgress;
        this.snoozed = snoozed;
        this.done = done;
        this.closedWithoutDone = closedWithoutDone;
        this.overdue = overdue;
    }

    public long getTotal() {
        return total;
    }

    public long getOpen() {
        return open;
    }

    public long getInProgress() {
        return inProgress;
    }

    public long getSnoozed() {
        return snoozed;
    }

    public long getDone() {
        return done;
    }

    public long getClosedWithoutDone() {
        return closedWithoutDone;
    }

    public long getOverdue() {
        return overdue;
    }
}
