package com.rnd.bridge.crm.controller;

import com.rnd.bridge.crm.CrmSourceResult;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP-safe view of all CRM sources' sync outcomes - top-level fields are sums across {@code
 * sources} (same meaning as the single-source response this replaces); {@code sources} is additive.
 */
public record CrmSyncResponse(
        int read, int published, int rejected, List<String> errors, int skippedAtRead,
        List<CrmSourceResult> sources) {

    public static CrmSyncResponse from(List<CrmSourceResult> sources) {
        int read = 0;
        int published = 0;
        int rejected = 0;
        int skippedAtRead = 0;
        List<String> errors = new ArrayList<>();
        for (CrmSourceResult source : sources) {
            read += source.read();
            published += source.published();
            rejected += source.rejected();
            skippedAtRead += source.skippedAtRead();
            errors.addAll(source.errors());
        }
        return new CrmSyncResponse(read, published, rejected, errors, skippedAtRead, sources);
    }
}
