package com.rnd.bridge.intake.unstructured.controller;

import com.rnd.bridge.intake.unstructured.dto.RawObjectEvent;
import com.rnd.bridge.intake.unstructured.dto.SourceType;
import com.rnd.bridge.intake.unstructured.service.UnstructuredIntakeService;
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
    public List<RawObjectEvent> run(@PathVariable SourceType sourceType) {
        return intakeService.run(sourceType);
    }
}
