// Violation example for the architecture-review skill's test scenarios (see ../../TEST-SCENARIOS.md).
// Expected finding: Critical — Controller bypasses Service to reach Repository (CLAUDE.md §5).
package com.rnd.inbox.controller;

import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskRepository taskRepository; // <-- Controller talking straight to Repository

    @GetMapping("/{id}")
    public Task getTask(@PathVariable Long id) {
        return taskRepository.findById(id).orElseThrow();
    }
}
