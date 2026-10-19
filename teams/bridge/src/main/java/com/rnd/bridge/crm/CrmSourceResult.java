package com.rnd.bridge.crm;

import java.util.List;

/**
 * One CRM source's sync outcome. {@code error} is null on success; on a whole-source failure
 * every count/list is zero/empty and {@code error} carries the exception's message.
 */
public record CrmSourceResult(
        String source,
        int read,
        int published,
        int rejected,
        List<String> errors,
        int skippedAtRead,
        String error) {
}
