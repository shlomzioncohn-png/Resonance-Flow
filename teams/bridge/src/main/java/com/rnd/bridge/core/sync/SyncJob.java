package com.rnd.bridge.core.sync;

import com.rnd.bridge.core.IntakeResult;

/**
 * One scheduled sync source - CRM, or an {@code UnstructuredIntakeController}-backed source like
 * email/calendar/drive. {@link SyncJobScheduler} collects every bean of this type and owns the
 * actual cron registration, enable/disable check, and failure handling, so an implementation only
 * has to say what it is and how to run once.
 */
public interface SyncJob {

    /** A short, stable identifier for log lines - doesn't need to be globally unique. */
    String name();

    boolean enabled();

    /** Read whenever {@link #enabled()} is true; ignored entirely otherwise. */
    String cron();

    IntakeResult runOnce();
}
