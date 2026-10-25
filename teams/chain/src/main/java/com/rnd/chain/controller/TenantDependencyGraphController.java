package com.rnd.chain.controller;

import com.rnd.chain.dto.TenantDependencyGraphResponse;
import com.rnd.chain.service.TenantDependencyGraphService;

import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/tenants")
@Validated
public class TenantDependencyGraphController {

    private final TenantDependencyGraphService tenantDependencyGraphService;

    public TenantDependencyGraphController(
            TenantDependencyGraphService tenantDependencyGraphService) {

        this.tenantDependencyGraphService =
                tenantDependencyGraphService;
    }

    @GetMapping("/{tenantId}/dependency-graph")
    public ResponseEntity<TenantDependencyGraphResponse> getDependencyGraph(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "500")
            @Min(1)
            int limit) {

        TenantDependencyGraphResponse response =
                tenantDependencyGraphService.getDependencyGraph(
                        tenantId,
                        limit);

        return ResponseEntity.ok(response);
    }
}