package com.rnd.bridge.crm;

import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.repository.DealRepository;
import org.springframework.stereotype.Component;

import java.util.stream.Stream;

/**
 * Generic {@link SourceReader} entry point, for {@code AbstractIntakeLane}'s
 * {@code run(ReadCursor, Instant)} overload (used by {@code CrmLaneIntegrationTest} and any future
 * generic caller). {@code CrmBridgeController} and {@code CrmSyncScheduler} don't go through this:
 * each takes one {@code DealSnapshot} per run and calls the {@code run(Stream, Instant)} overload
 * directly, so the deals and the {@code asOf} they publish are guaranteed to come from the same
 * fetch. This reader exists so a caller using the {@code ReadCursor} overload sees the same
 * latest-per-deal behavior those two do, not a second, inconsistent one.
 */
@Component
public class CrmSourceReader implements SourceReader<Deal> {

    private final DealRepository dealRepository;

    public CrmSourceReader(DealRepository dealRepository) {
        this.dealRepository = dealRepository;
    }

    @Override
    public Stream<Deal> read(ReadCursor cursor) {
        // cursor is accepted but ignored: only full sync exists today - the
        // synthetic feed has no since-timestamp query to run an incremental
        // read against. Wiring the parameter through now means adding real
        // incremental support later doesn't change this method's signature.
        return dealRepository.snapshot().latestPerDeal().stream();
    }
}
