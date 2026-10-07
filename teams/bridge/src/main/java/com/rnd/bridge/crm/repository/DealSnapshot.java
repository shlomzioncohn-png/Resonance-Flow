package com.rnd.bridge.crm.repository;

import com.rnd.bridge.crm.model.Deal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One fetch's worth of CRM deal data - every history row {@link DealRepository#snapshot()} saw,
 * grouped by deal, plus the instant the fetch was taken. Immutable: {@link #asOf()} and every
 * accessor here come from the same fetch, so nothing here can disagree with itself the way
 * {@code asOf()} and {@code findAll()} used to when they were served from a repository field
 * cached across separate calls (see {@link DealRepository}'s old per-instance-cached finder
 * methods, removed in favor of this type, and finding F1 in the CRM-lane audit).
 */
public record DealSnapshot(
        Instant asOf,
        List<Deal> allDeals,
        Map<String, List<Deal>> historyByDealId,
        List<String> dealIds) {

    public List<Deal> findHistoryByDealId(String dealId) {
        return historyByDealId.getOrDefault(dealId, List.of());
    }

    public Optional<Deal> findLatestByDealId(String dealId) {
        List<Deal> history = findHistoryByDealId(dealId);
        return history.isEmpty() ? Optional.empty() : Optional.of(history.get(history.size() - 1));
    }
}
