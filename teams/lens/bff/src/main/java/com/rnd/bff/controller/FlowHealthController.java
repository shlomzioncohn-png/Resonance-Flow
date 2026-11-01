package com.rnd.bff.controller;

import com.rnd.bff.dto.FlowHealthDTO;
import com.rnd.bff.service.FlowHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manager flow-health page (read-only). Gateway forwards GET /api/manager/flow-health here as
 * GET /manager/flow-health, with the caller's identity in X-User-Id.
 */
@RestController
@RequestMapping("/manager/flow-health")
@Tag(name = "Manager Flow Health", description = "Called only by the Gateway, never directly by the Frontend")
public class FlowHealthController {

    private static final Logger logger = LoggerFactory.getLogger(FlowHealthController.class);

    private final FlowHealthService flowHealthService;

    public FlowHealthController(FlowHealthService flowHealthService) {
        this.flowHealthService = flowHealthService;
    }

    @Operation(summary = "Flow-health flags",
            description = "Patterns over time found by Chain's analytics job, with task names. A recommendation only - nothing here changes tasks. 503 if Chain or Inbox is unavailable.")
    @GetMapping
    public ResponseEntity<FlowHealthDTO> getFlowHealth(@RequestHeader("X-User-Id") String userId) {
        logger.info("Manager flow-health request - actorId: {}", userId);
        return ResponseEntity.ok(flowHealthService.getFlowHealth(userId));
    }
}
