// Violation example for the architecture-review skill's test scenarios (see ../../TEST-SCENARIOS.md).
// Expected finding: Critical — business logic in Controller (CLAUDE.md §4/§5).
package com.rnd.inbox.controller;

import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @PostMapping("/{id}/complete")
    public Task completeTask(@PathVariable Long id) {
        Task task = taskService.getTaskEntity(id);

        // business rule decided right here in the controller instead of in TaskService
        if (task.getStatus() == TaskStatus.BLOCKED || task.hasOpenSubtasks()) {
            throw new IllegalStateException("cannot complete a blocked task with open subtasks");
        }
        task.setStatus(TaskStatus.DONE);
        return taskService.save(task);
    }
}
