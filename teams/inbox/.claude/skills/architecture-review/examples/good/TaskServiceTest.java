// Example only — illustrates a compliant slice for the architecture-review skill's test
// scenarios. Not wired into the Maven build.
package com.rnd.inbox.service;

import com.rnd.inbox.dto.TaskCreateRequestDto;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.exception.TaskNotFoundException;
import com.rnd.inbox.mapper.TaskMapper;
import com.rnd.inbox.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskMapper taskMapper;

    @InjectMocks
    private TaskService taskService;

    @Test
    void createTask_whenRequestValid_savesAndReturnsTask() {
        TaskCreateRequestDto request = new TaskCreateRequestDto();
        Task entity = new Task();
        Task saved = new Task();
        saved.setId(1L);

        when(taskMapper.toEntity(request)).thenReturn(entity);
        when(taskRepository.save(entity)).thenReturn(saved);

        taskService.createTask(request);

        assertThat(saved.getId()).isEqualTo(1L);
    }

    @Test
    void getTask_whenTaskMissing_throwsTaskNotFoundException() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.getTask(99L))
                .isInstanceOf(TaskNotFoundException.class);
    }
}
