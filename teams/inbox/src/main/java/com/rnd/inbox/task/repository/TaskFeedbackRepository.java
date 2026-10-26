package com.rnd.inbox.task.repository;
import com.rnd.inbox.task.model.TaskFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TaskFeedbackRepository extends JpaRepository<TaskFeedback, UUID> {
}
