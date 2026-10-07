package com.rnd.bridge.crm.repository;

/**
 * Read access to the CRM deal feed.
 * <p>
 * The feed is append-only stage events: a deal id can appear more than once, once per stage
 * transition. Implementations must return records ordered by {@code stageChangedAt} ascending
 * everywhere order is observable, so the re-open sequence (e.g. CLOSED_WON followed by a later
 * NEGOTIATION) replays in the order it actually happened.
 * <p>
 * {@link #snapshot()} is the one entry point: it performs one fresh fetch and returns an
 * immutable {@link DealSnapshot} whose {@code asOf()} and every deal in it agree with each other,
 * because they all came from that one fetch - never cached across separate calls the way a
 * long-lived singleton bean's own field would be (this interface used to also expose {@code
 * asOf()}/{@code findAll()}/{@code findHistoryByDealId()}/{@code findLatestByDealId()}/{@code
 * findAllDealIds()} finder methods backed by exactly that kind of per-instance cache - removed
 * entirely; see CRM-lane audit finding F1). Call {@link #snapshot()} once per sync (once per
 * {@code POST /bridge/crm/sync}, once per scheduled run) and read everything else off the
 * returned snapshot.
 */
public interface DealRepository {

    DealSnapshot snapshot();
}
