package com.rnd.bridge.media.controller;

import com.rnd.bridge.media.MediaIntakeOrchestrator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bridge/media")
public class MediaIntakeController {

    private final MediaIntakeOrchestrator orchestrator;

    public MediaIntakeController(MediaIntakeOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/run")
    public MediaIntakeOrchestrator.IntakeSummary runIntake() {
        return orchestrator.runIntake();
    }
}
