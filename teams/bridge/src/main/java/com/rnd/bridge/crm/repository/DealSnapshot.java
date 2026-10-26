package com.rnd.bridge.crm.repository;

import com.rnd.bridge.crm.model.Deal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One fetch's worth of CRM deal data - every history row, grouped by deal, plus {@code asOf} and
 * {@code skippedAtRead}. Immutable: everything here comes from the same fetch, never cached across separate calls (F1).
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
     * One {@link Deal} per deal id - its latest state by {@code stageChangedAt}. Publish this, not
     * {@link #allDeals()}: Inbox's ingestion is insert-only, so one event per deal avoids a stale re-publish (F2).
     */
    public List<Deal> latestPerDeal() {
        List<Deal> latest = new ArrayList<>(dealIds.size());
        for (String dealId : dealIds) {
            findLatestByDealId(dealId).ifPresent(latest::add);
        }
        return latest;
    }
}
