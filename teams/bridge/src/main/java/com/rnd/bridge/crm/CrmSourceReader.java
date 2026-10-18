package com.rnd.bridge.crm;

import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.repository.DealRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Stream;

/**
 * Generic {@link SourceReader} entry point for {@code AbstractIntakeLane}'s {@code
 * run(ReadCursor, Instant)} overload (test-only today). Single CRM source only - {@link
 * #read(ReadCursor)} throws if more than one is active; {@code CrmSyncService} is the multi-source path.
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
        // cursor ignored - only full sync exists today.
        return dealRepositories.get(0).snapshot().latestPerDeal().stream();
    }
}
