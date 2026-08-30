// Example only — illustrates a compliant slice for the architecture-review skill's test
// scenarios. Not wired into the Maven build.
package com.rnd.inbox.repository;

import com.rnd.inbox.model.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {
}
