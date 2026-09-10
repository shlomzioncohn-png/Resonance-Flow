package com.rnd.bridge.intake.controller;

import com.rnd.bridge.intake.service.UnstructuredIntakeService;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bridge/intake")
public class UnstructuredIntakeController {

    private final UnstructuredIntakeService intakeService;

    public UnstructuredIntakeController(UnstructuredIntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping("/{sourceType}/run")
    public List<RawObjectEvent> run(@PathVariable RawSourceType sourceType) {
        return intakeService.run(sourceType);
    }
}
