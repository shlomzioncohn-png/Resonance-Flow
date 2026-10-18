package com.rnd.bridge.crm;

import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.repository.DealRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Stream;

/**
 * Generic {@link SourceReader} entry point, for {@code AbstractIntakeLane}'s
 * {@code run(ReadCursor, Instant)} overload (used by {@code CrmLaneIntegrationTest} and any future
 * generic caller). {@code CrmBridgeController} and {@code CrmSyncJob} don't go through this:
 * each takes one {@code DealSnapshot} per run and calls the {@code run(Stream, Instant)} overload
 * directly, so the deals and the {@code asOf} they publish are guaranteed to come from the same
 * fetch. This reader exists so a caller using the {@code ReadCursor} overload sees the same
 * latest-per-deal behavior those two do, not a second, inconsistent one.
 * <p>
 * <b>Single-source only.</b> {@code SourceReader<Deal>.read(ReadCursor)} returns one {@code
 * Stream<Deal>} with no way to attach a per-source {@code asOf} or surface a per-source failure -
 * it cannot give hubspot+salesforce-together the same failure isolation and independent-snapshot
 * guarantees {@code CrmSyncService} does. Rather than build a second, partially-compatible
 * multi-source path here, this reader keeps exactly its original single-repository behavior and
 * fails loudly in {@link #read(ReadCursor)} - not the constructor, so the application still starts
 * with both CRM profiles active - if it is ever asked to read with more than one source active.
 * {@code CrmSyncService} (constructed over the same {@code List<DealRepository>}) is the one
 * multi-source sync path, used by both the scheduled job and the HTTP endpoint.
 */
@Component
public class CrmSourceReader implements SourceReader<Deal> {

    private final List<DealRepository> dealRepositories;

    public CrmSourceReader(List<DealRepository> dealRepositories) {
        this.dealRepositories = dealRepositories;
    }

    @Override
    public Stream<Deal> read(ReadCursor cursor) {
        if (dealRepositories.size() > 1) {
            throw new IllegalStateException(
                    "CrmSourceReader supports a single CRM source; use CrmSyncService");
        }
        // cursor is accepted but ignored: only full sync exists today - the
        // synthetic feed has no since-timestamp query to run an incremental
        // read against. Wiring the parameter through now means adding real
        // incremental support later doesn't change this method's signature.
        return dealRepositories.get(0).snapshot().latestPerDeal().stream();
    }
}
