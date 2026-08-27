package com.rnd.bridge.core.error;

/**
 * Decides whether a failed record aborts the run or is recorded and
 * skipped so the rest of the batch still gets processed.
 */
@FunctionalInterface
public interface ErrorPolicy {

    ErrorPolicy RECORD_AND_CONTINUE = exception -> false;

    boolean shouldAbort(LaneException exception);
}
