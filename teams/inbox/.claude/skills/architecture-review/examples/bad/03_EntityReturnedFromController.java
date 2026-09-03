// Violation example for the architecture-review skill's test scenarios (see ../../TEST-SCENARIOS.md).
// Expected finding: Major — Entity used in the wrong layer (CLAUDE.md §4).
package com.rnd.inbox.controller;

import com.rnd.inbox.model.entity.Task; // persistence entity, not a DTO
import com.rnd.inbox.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @GetMapping("/{id}")
    public Task getTask(@PathVariable Long id) { // returns the JPA entity straight over the API
        return taskService.getTaskEntity(id);
    }
}
