package com.rnd.bridge.crm.controller;

import com.rnd.bridge.crm.CrmLane;
import com.rnd.bridge.crm.repository.DealRepository;
import com.rnd.bridge.crm.repository.DealSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Thin HTTP trigger for the CRM lane: {@code POST /bridge/crm/sync} takes one {@link DealSnapshot}
 * and runs {@link CrmLane} against it. No auth; concurrent POSTs are not safe once Kafka is wired (no single-flight guard).
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
        return CrmSyncResponse.from(
                crmLane.run(snapshot.latestPerDeal().stream(), snapshot.asOf()),
                snapshot.skippedAtRead());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleSnapshotFailure(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "CRM sync failed: " + e.getMessage()));
    }
}
