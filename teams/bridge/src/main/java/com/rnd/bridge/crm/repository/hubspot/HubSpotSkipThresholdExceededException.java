package com.rnd.bridge.crm.repository.hubspot;

/**
 * Thrown by {@link HubSpotDealRepository#snapshot()} when {@code skippedAtRead} exceeds
 * {@code bridge.crm.hubspot.max-skipped-ratio} - fails the whole run instead of publishing a partial snapshot.
 */
public class HubSpotSkipThresholdExceededException extends RuntimeException {

    public HubSpotSkipThresholdExceededException(String message) {
        super(message);
    }
}
