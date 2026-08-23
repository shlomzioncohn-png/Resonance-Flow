package com.rnd.bridge.repository;

import com.rnd.bridge.crm.model.Deal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read access to the CRM deal feed.
 * <p>
 * The feed is append-only stage events: a deal id can appear more than once,
 * once per stage transition. Implementations must return records ordered by
 * {@code stageChangedAt} ascending everywhere order is observable, so the
 * re-open sequence (e.g. CLOSED_WON followed by a later NEGOTIATION) replays
 * in the order it actually happened.
 * <p>
 * {@link #asOf()} - not the wall clock - is the time basis for anything
 * time-dependent in the CRM-to-Task mapping (e.g. urgency).
 */
public interface DealRepository {

    Instant asOf();

    List<Deal> findAll();

    List<Deal> findHistoryByDealId(String dealId);

    Optional<Deal> findLatestByDealId(String dealId);

    List<String> findAllDealIds();
}
