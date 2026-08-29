// Example only — illustrates a compliant slice for the architecture-review skill's test
// scenarios. Not wired into the Maven build.
package com.rnd.inbox.mapper;

import com.rnd.inbox.dto.TaskCreateRequestDto;
import com.rnd.inbox.dto.TaskResponseDto;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.enums.TaskStatus;
import org.springframework.stereotype.Component;

@Component
public class TaskMapper {

    public Task toEntity(TaskCreateRequestDto dto) {
        Task task = new Task();
        task.setTitle(dto.getTitle());
        task.setDescription(dto.getDescription());
        task.setStatus(TaskStatus.CREATED);
        return task;
    }

    public TaskResponseDto toResponseDto(Task task) {
        return new TaskResponseDto(task.getId(), task.getTitle(), task.getDescription(), task.getStatus().name());
    }
}
