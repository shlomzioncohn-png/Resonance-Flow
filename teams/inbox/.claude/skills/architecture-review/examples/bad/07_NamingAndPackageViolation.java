// Violation example for the architecture-review skill's test scenarios (see ../../TEST-SCENARIOS.md).
// Expected finding: Minor — naming/package-structure violation (CLAUDE.md §6).
package com.rnd.inbox.service;

import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Named like a repository/DAO and holds real business logic, but sits in `service/` — the name
// actively misleads about what this class does and where its logic lives.
@Service
@RequiredArgsConstructor
public class TaskDao {

    private final TaskRepository taskRepository;

    public Task markUrgent(Long id) {
        Task task = taskRepository.findById(id).orElseThrow();
        task.setTitle("[URGENT] " + task.getTitle());
        return taskRepository.save(task);
    }
}
