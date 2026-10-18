package com.rnd.bridge.crm.controller;

import com.rnd.bridge.core.IntakeResult;

import java.util.List;

/**
 * HTTP-safe view of an {@link IntakeResult}, plus {@code skippedAtRead} (deals that never reached
 * the lane - F9). Maps each {@code LaneException} to its message only, not the whole stack trace.
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
