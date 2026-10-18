package com.rnd.bridge.crm.repository;

import com.rnd.bridge.crm.model.Deal;

import java.util.Optional;

/**
 * Read access to the CRM deal feed: append-only stage events, records ordered by {@code
 * stageChangedAt} ascending. Call {@link #snapshot()} once per sync and read everything off it.
 */
public interface DealRepository {

    /**
     * Short, stable label for this source ({@code "hubspot"}/{@code "salesforce"}/{@code "json"}),
     * for per-source reporting only - never used to build {@code sourceRef} (see {@link Deal#sourceRefPrefix()}).
     */
    String source();

    /** Performs one fresh fetch and returns an immutable {@link DealSnapshot} - never cached across calls. */
    DealSnapshot snapshot();

    /**
     * Fetches one deal fresh from the source right now; {@link Optional#empty()} means it doesn't
     * exist, not a failure.
     *
     * @throws IllegalArgumentException if {@code dealId} is null or blank
     */
    Optional<Deal> fetchDeal(String dealId);
}
