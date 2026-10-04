package com.rnd.bridge.crm.repository;

import com.rnd.bridge.crm.model.Deal;

import java.time.Instant;
import java.util.List;

/**
 * The shape of {@code synthetic-deals.json}'s envelope, shared by
 * {@link JsonDealRepository} and {@link SyntheticDealsLoader} so the two
 * paths that read the same file cannot silently drift apart in what they
 * expect it to contain.
 */
record SyntheticDealsFeed(
        String datasetVersion,
        Instant asOf,
        String notes,
        List<Deal> deals) {
}
