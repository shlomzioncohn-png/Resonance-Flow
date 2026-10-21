package com.rnd.inbox.project;

import java.time.Instant;
import java.util.UUID;

public interface ProjectTaskStats {

    UUID getProjectId();

    long getTotal();

    long getOpen();

    long getInProgress();

    long getSnoozed();

    long getDone();

    long getClosedWithoutDone();

    long getOverdue();

    Instant getLastTaskUpdatedAt();
}
