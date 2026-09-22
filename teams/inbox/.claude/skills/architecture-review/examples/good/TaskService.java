// Example only — illustrates a compliant slice for the architecture-review skill's test
// scenarios. Not wired into the Maven build.
package com.rnd.inbox.service;

import com.rnd.inbox.dto.TaskCreateRequestDto;
import com.rnd.inbox.dto.TaskResponseDto;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.exception.InvalidTaskStateException;
import com.rnd.inbox.exception.TaskNotFoundException;
import com.rnd.inbox.mapper.TaskMapper;
import com.rnd.inbox.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskMapper taskMapper;

    @Transactional
    public TaskResponseDto createTask(TaskCreateRequestDto request) {
        Task task = taskMapper.toEntity(request);
        Task saved = taskRepository.save(task);
        log.info("Task {} created", saved.getId());
        return taskMapper.toResponseDto(saved);
    }

    public TaskResponseDto getTask(Long id) {
        Task task = findOrThrow(id);
        return taskMapper.toResponseDto(task);
    }

    @Transactional
    public TaskResponseDto completeTask(Long id) {
        Task task = findOrThrow(id);
        if (task.hasOpenSubtasks()) {
            throw new InvalidTaskStateException("Task " + id + " has open sub-tasks");
        }
        task.setStatus(TaskStatus.DONE);
        Task saved = taskRepository.save(task);
        log.info("Task {} marked complete", id);
        return taskMapper.toResponseDto(saved);
    }

    private Task findOrThrow(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new TaskNotFoundException("Task " + id + " not found"));
    }
}
