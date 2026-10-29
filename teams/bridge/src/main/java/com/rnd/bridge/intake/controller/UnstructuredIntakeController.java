package com.rnd.bridge.intake.controller;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.intake.service.UnstructuredIntakeService;
import com.rnd.bridge.raw.model.RawSourceType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger for one unstructured source: {@code POST /api/bridge/intake/{sourceType}/run}.
 * Off unless bridge.manual-triggers.enabled=true - it has no auth, and the scheduled
 * IntakeSyncJobs call {@link UnstructuredIntakeService} directly without it.
 */
@ConditionalOnProperty(name = "bridge.manual-triggers.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/bridge/intake")
public class UnstructuredIntakeController {

    private final UnstructuredIntakeService intakeService;

    public UnstructuredIntakeController(UnstructuredIntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping("/{sourceType}/run")
    public IntakeResult run(@PathVariable RawSourceType sourceType) {
        return intakeService.run(sourceType);
    }
}
