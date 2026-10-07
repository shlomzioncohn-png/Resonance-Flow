package com.rnd.bridge.crm.repository;

import com.rnd.bridge.crm.model.Deal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One fetch's worth of CRM deal data - every history row {@link DealRepository#snapshot()} saw,
 * grouped by deal, plus the instant the fetch was taken, plus how many records were skipped while
 * reading it (a deal that failed to parse - see {@code HubSpotDealRepository} - never makes it
 * into this snapshot at all). Immutable: {@link #asOf()} and every accessor here come from the
 * same fetch, so nothing here can disagree with itself the way {@code asOf()} and {@code
 * findAll()} used to when they were served from a repository field cached across separate calls
 * (see {@link DealRepository}'s old per-instance-cached finder methods, removed in favor of this
 * type, and finding F1 in the CRM-lane audit).
 */
public record DealSnapshot(
        Instant asOf,
        List<Deal> allDeals,
        Map<String, List<Deal>> historyByDealId,
        List<String> dealIds,
        int skippedAtRead) {

    public List<Deal> findHistoryByDealId(String dealId) {
        return historyByDealId.getOrDefault(dealId, List.of());
    }

    public Optional<Deal> findLatestByDealId(String dealId) {
        List<Deal> history = findHistoryByDealId(dealId);
        return history.isEmpty() ? Optional.empty() : Optional.of(history.get(history.size() - 1));
    }

    /**
     * One {@link Deal} per deal id - its latest state by {@code stageChangedAt} - in the same
     * deal-id order as {@link #dealIds()}. {@link #historyByDealId()}'s lists are stored
     * oldest-first (see {@link DealRepository}'s own contract), so "latest" is each list's last
     * element.
     * <p>
     * This is what a lane should publish: one event per deal, not one per stage transition.
     * Publishing {@link #allDeals()} instead produces a wrong status/urgency the moment Inbox
     * receives a later record for a {@code sourceRef} it has already seen, since Inbox's ingestion
     * is insert-or-return-existing, not an upsert (finding F2 in the CRM-lane audit).
     */
    public List<Deal> latestPerDeal() {
        List<Deal> latest = new ArrayList<>(dealIds.size());
        for (String dealId : dealIds) {
            findLatestByDealId(dealId).ifPresent(latest::add);
        }
        return latest;
    }
}
