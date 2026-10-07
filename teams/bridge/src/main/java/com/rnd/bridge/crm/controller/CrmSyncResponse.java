package com.rnd.bridge.crm.controller;

import com.rnd.bridge.core.IntakeResult;

import java.util.List;

/**
 * HTTP-safe view of an {@link IntakeResult}, plus {@code skippedAtRead} - deals that never made it
 * into the lane at all because they failed to parse while reading from HubSpot (see
 * {@code HubSpotDealRepository}), so they can't appear in {@code IntakeResult}'s own counts.
 * {@code IntakeResult} carries {@code List<LaneException>}, which Jackson would otherwise try to
 * serialize whole - stack traces included. This maps each exception to its message only.
 */
public record CrmSyncResponse(int read, int published, int rejected, List<String> errors, int skippedAtRead) {

    public static CrmSyncResponse from(IntakeResult result, int skippedAtRead) {
        return new CrmSyncResponse(
                result.read(),
                result.published(),
                result.rejected(),
                result.errors().stream().map(Throwable::getMessage).toList(),
                skippedAtRead);
    }
}
