package com.rnd.bridge.crm.controller;

import com.rnd.bridge.crm.CrmSourceResult;
import com.rnd.bridge.crm.CrmSyncService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Thin HTTP trigger for the CRM lane: {@code POST /bridge/crm/sync} runs every active source via
 * {@link CrmSyncService}. 200 if at least one source succeeded; 503 only if all failed.
 */
@ConditionalOnProperty(name = "bridge.manual-triggers.enabled", havingValue = "true")
@RestController
@RequestMapping("/bridge/crm")
public class CrmBridgeController {

    private final CrmSyncService crmSyncService;

    public CrmBridgeController(CrmSyncService crmSyncService) {
        this.crmSyncService = crmSyncService;
    }

    @PostMapping("/sync")
    public ResponseEntity<CrmSyncResponse> sync() {
        List<CrmSourceResult> results = crmSyncService.syncAll();
        CrmSyncResponse response = CrmSyncResponse.from(results);

        boolean anySucceeded = results.isEmpty() || results.stream().anyMatch(result -> result.error() == null);
        HttpStatus status = anySucceeded ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }
}
