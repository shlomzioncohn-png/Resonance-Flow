package com.rnd.bridge.core.sync;

import com.rnd.bridge.core.IntakeResult;

/**
 * One scheduled sync source (CRM, or an {@code UnstructuredIntakeService}-backed source).
 * {@link SyncJobScheduler} collects every bean of this type and owns cron registration + failure handling.
 */
public interface SyncJob {

    /** A short, stable identifier for log lines - doesn't need to be globally unique. */
    String name();

    boolean enabled();

    /** Read whenever {@link #enabled()} is true; ignored entirely otherwise. */
    String cron();

    IntakeResult runOnce();
}
