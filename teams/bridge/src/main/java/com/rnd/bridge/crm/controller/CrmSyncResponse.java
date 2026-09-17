package com.rnd.bridge.crm.controller;

import com.rnd.bridge.core.IntakeResult;

import java.util.List;

/**
 * HTTP-safe view of an {@link IntakeResult}. {@code IntakeResult} carries
 * {@code List<LaneException>}, which Jackson would otherwise try to
 * serialize whole - stack traces included. This maps each exception to its
 * message only.
 */
public record CrmSyncResponse(int read, int published, int rejected, List<String> errors) {

    public static CrmSyncResponse from(IntakeResult result) {
        return new CrmSyncResponse(
                result.read(),
                result.published(),
                result.rejected(),
                result.errors().stream().map(Throwable::getMessage).toList());
    }
}
