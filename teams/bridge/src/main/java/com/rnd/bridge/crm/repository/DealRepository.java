package com.rnd.bridge.crm.repository;

import com.rnd.bridge.crm.model.Deal;

import java.util.Optional;

/**
 * Read access to the CRM deal feed.
 * <p>
 * The feed is append-only stage events: a deal id can appear more than once, once per stage
 * transition. Implementations must return records ordered by {@code stageChangedAt} ascending
 * everywhere order is observable, so the re-open sequence (e.g. CLOSED_WON followed by a later
 * NEGOTIATION) replays in the order it actually happened.
 * <p>
 * {@link #snapshot()} is the entry point for a full sync: it performs one fresh fetch and returns
 * an immutable {@link DealSnapshot} whose {@code asOf()} and every deal in it agree with each
 * other, because they all came from that one fetch - never cached across separate calls the way a
 * long-lived singleton bean's own field would be (this interface used to also expose {@code
 * asOf()}/{@code findAll()}/{@code findHistoryByDealId()}/{@code findLatestByDealId()}/{@code
 * findAllDealIds()} finder methods backed by exactly that kind of per-instance cache - removed
 * entirely; see CRM-lane audit finding F1). Call {@link #snapshot()} once per sync (once per
 * {@code POST /bridge/crm/sync}, once per scheduled run) and read everything else off the
 * returned snapshot.
 */
public interface DealRepository {

    DealSnapshot snapshot();

    /**
     * Fetches exactly one deal, fresh from the source, right now.
     * <p>
     * <b>Fresh.</b> Always performs a real read against the source for this call - no cache, and
     * never reuses data an earlier {@link #snapshot()} or {@link #fetchDeal(String)} call already
     * fetched.
     * <p>
     * <b>Latest state.</b> Returns the same {@link Deal} {@link DealSnapshot#latestPerDeal()}
     * would contain for this id if {@link #snapshot()} were called at this same instant instead -
     * same mapping rules, same stage resolution. There is no separate {@code asOf()} to read for a
     * single deal the way {@link DealSnapshot} carries one for a whole fetch: "as of" here is
     * simply the instant this call executed, which is also the instant {@code stageChangedAt} on
     * the returned {@link Deal} is measured against - there's nothing else in the fetch for it to
     * disagree with.
     * <p>
     * <b>Not found vs. failure.</b> {@link Optional#empty()} means the deal does not exist at the
     * source right now (e.g. HubSpot's {@code GET /crm/v3/objects/deals/{dealId}} returning a 404)
     * - this is an expected outcome, not an error. Any other failure (authentication, exhausting
     * the retry budget, a malformed record, an unrecognized stage) throws the same exception types
     * {@link #snapshot()} throws for the same underlying problem.
     * <p>
     * <b>Why this exists.</b> This method prepares a future real-time (webhook) path for CRM
     * sources - looking up one deal's current state on demand, without paying for a full {@link
     * #snapshot()} - and is not itself wired to any webhook, endpoint, or event listener yet.
     *
     * @throws IllegalArgumentException if {@code dealId} is null or blank
     */
    Optional<Deal> fetchDeal(String dealId);
}
