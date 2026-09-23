
package com.rnd.chain.controller;

import com.rnd.chain.dto.TaskEligibilityResponse;
import com.rnd.chain.service.TaskEligibilityService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/tasks")
@Tag(
        name = "Task Eligibility",
        description = "Query task scheduling eligibility state"
)
public class TaskEligibilityController {

    private final TaskEligibilityService taskEligibilityService;

    public TaskEligibilityController(
            TaskEligibilityService taskEligibilityService) {

        this.taskEligibilityService = taskEligibilityService;
    }

    @Operation(
            summary = "Get task eligibility",
            description = "Returns the current readiness and blocking state of the specified task"
    )
    @GetMapping("/{taskId}/eligibility")
    public ResponseEntity<TaskEligibilityResponse> getTaskEligibility(
            @PathVariable UUID taskId) {

        TaskEligibilityResponse eligibility =
                taskEligibilityService.getTaskEligibility(taskId);

        return ResponseEntity.ok(eligibility);
    }
}