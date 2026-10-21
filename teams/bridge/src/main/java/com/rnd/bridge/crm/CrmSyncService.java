package com.rnd.bridge.crm;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.crm.repository.DealRepository;
import com.rnd.bridge.crm.repository.DealSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs every active CRM source's own snapshot + {@link CrmLane#run} sequentially, isolating one
 * source's failure from the rest. The one multi-source sync path for both the scheduled job and the HTTP endpoint.
 */
@Component
public class CrmSyncService {

    private static final Logger log = LoggerFactory.getLogger(CrmSyncService.class);

    private final List<DealRepository> dealRepositories;
    private final CrmLane crmLane;

    public CrmSyncService(List<DealRepository> dealRepositories, CrmLane crmLane) {
        this.dealRepositories = dealRepositories;
        this.crmLane = crmLane;
    }

    public List<CrmSourceResult> syncAll() {
        List<CrmSourceResult> results = new ArrayList<>();
        for (DealRepository repository : dealRepositories) {
            results.add(syncOne(repository));
        }
        return results;
    }

    private CrmSourceResult syncOne(DealRepository repository) {
        String source = repository.source();
        try {
            DealSnapshot snapshot = repository.snapshot();
            IntakeResult result = crmLane.run(snapshot.latestPerDeal().stream(), snapshot.asOf());
            return new CrmSourceResult(
                    source,
                    result.read(),
                    result.published(),
                    result.rejected(),
                    result.errors().stream().map(Throwable::getMessage).toList(),
                    snapshot.skippedAtRead(),
                    null);
        } catch (RuntimeException e) {
            log.error("CRM sync failed for source '{}'", source, e);
            return new CrmSourceResult(source, 0, 0, 0, List.of(), 0, e.getMessage());
        }
    }
}
