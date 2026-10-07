package com.rnd.bridge.crm.controller;

import com.rnd.bridge.crm.CrmLane;
import com.rnd.bridge.crm.repository.DealRepository;
import com.rnd.bridge.crm.repository.DealSnapshot;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP trigger for the CRM lane. No mapping, filtering, or decisions
 * of its own - {@link CrmLane} already does all of that; this only calls it
 * and shapes the result for JSON.
 * <p>
 * No authentication: an endpoint that triggers a sync should not be openly
 * exposed in production, but there is no RBAC in the platform at this stage.
 * Noting the gap rather than inventing a mechanism here.
 * <p>
 * Concurrent POSTs run concurrent syncs against the same lane. Harmless with
 * the current {@code LoggingPayloadPublisher}; not harmless once Kafka is
 * wired (interleaved or duplicate publishes). Not solved here - needs a
 * single-flight guard or lock before that lands.
 * <p>
 * Takes exactly one {@link DealSnapshot} per request and runs the lane against its {@code
 * allDeals()}/{@code asOf()} together, via {@code CrmLane.run(Stream, Instant)} - never the
 * {@code ReadCursor} overload, which would fetch the deals and the {@code asOf} from two separate,
 * potentially different snapshots (see {@code HubSpotDealRepository.snapshot()}'s Javadoc: every
 * call is an independent fetch, so two calls can legitimately disagree).
 */
@RestController
@RequestMapping("/bridge/crm")
public class CrmBridgeController {

    private final CrmLane crmLane;
    private final DealRepository dealRepository;

    public CrmBridgeController(CrmLane crmLane, DealRepository dealRepository) {
        this.crmLane = crmLane;
        this.dealRepository = dealRepository;
    }

    @PostMapping("/sync")
    public CrmSyncResponse sync() {
        DealSnapshot snapshot = dealRepository.snapshot();
        return CrmSyncResponse.from(crmLane.run(snapshot.allDeals().stream(), snapshot.asOf()));
    }
}
