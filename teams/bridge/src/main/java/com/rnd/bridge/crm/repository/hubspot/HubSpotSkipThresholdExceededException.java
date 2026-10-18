package com.rnd.bridge.crm.repository.hubspot;

/**
 * Thrown by {@link HubSpotDealRepository#snapshot()} when the fraction of deals skipped while
 * reading ({@code skippedAtRead}) exceeds {@code bridge.crm.hubspot.max-skipped-ratio}. One invalid
 * stage used by many deals (or several at once) makes every one of those deals fail the same way,
 * inflating {@code skippedAtRead} well past "a few bad records" - at that point a partial snapshot
 * would look like a mostly-successful sync instead of the broken one it is, so this fails the whole
 * run instead of letting {@code CrmLane} publish what's left.
 */
public class HubSpotSkipThresholdExceededException extends RuntimeException {

    public HubSpotSkipThresholdExceededException(String message) {
        super(message);
    }
}
