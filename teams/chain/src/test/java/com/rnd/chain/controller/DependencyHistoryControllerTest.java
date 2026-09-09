package com.rnd.chain.controller;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.chain.testsupport.IncrementingClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Overrides the production Clock with a strictly-increasing one - see IncrementingClock for why. */
@SpringBootTest
@AutoConfigureMockMvc
class DependencyHistoryControllerTest {

    @TestConfiguration
    static class DeterministicClockConfig {
        // Named differently from MockDataConfig's "clock" bean to avoid a
        // BeanDefinitionOverrideException - @ConditionalOnMissingBean(Clock.class)
        // there runs before this class is even parsed, so it registers its own
        // bean regardless; @Primary makes autowiring still resolve to this one.
        @Bean
        @Primary
        Clock testClock() {
            return new IncrementingClock();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskDependencyService taskDependencyService;

    private UUID taskA;
    private UUID taskB;

    @BeforeEach
    void setUp() {
        taskA = UUID.randomUUID();
        taskB = UUID.randomUUID();
        taskRepository.save(newTask(taskA));
        taskRepository.save(newTask(taskB));
    }

    @Test
    void getHistory_returnsEmptyPage_whenTaskExistsWithNoChanges() throws Exception {
        mockMvc.perform(get("/tasks/{taskId}/dependencies/history", taskA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getHistory_returns404_whenTaskDoesNotExist() throws Exception {
        mockMvc.perform(get("/tasks/{taskId}/dependencies/history", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TaskNotFoundException"));
    }

    @Test
    void getHistory_returnsEntriesNewestFirst_afterAddAndRemove() throws Exception {
        taskDependencyService.addDependency(taskA, taskB);
        taskDependencyService.removeDependency(taskA, taskB);

        mockMvc.perform(get("/tasks/{taskId}/dependencies/history", taskA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].action").value("REMOVED"))
                .andExpect(jsonPath("$.content[1].action").value("ADDED"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void getHistory_paginates() throws Exception {
        UUID taskC = UUID.randomUUID();
        UUID taskD = UUID.randomUUID();
        taskRepository.save(newTask(taskC));
        taskRepository.save(newTask(taskD));
        taskDependencyService.addDependency(taskA, taskB);
        taskDependencyService.addDependency(taskA, taskC);
        taskDependencyService.addDependency(taskA, taskD);

        mockMvc.perform(get("/tasks/{taskId}/dependencies/history", taskA)
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void getHistory_rejectsSizeZero() throws Exception {
        mockMvc.perform(get("/tasks/{taskId}/dependencies/history", taskA).param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getHistory_rejectsSizeOver100() throws Exception {
        mockMvc.perform(get("/tasks/{taskId}/dependencies/history", taskA).param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getHistory_rejectsNegativePage() throws Exception {
        mockMvc.perform(get("/tasks/{taskId}/dependencies/history", taskA).param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    private static Task newTask(UUID id) {
        return Task.builder()
                .id(id)
                .ownerId("owner")
                .status(TaskStatus.ACTIVE)
                .isReady(true)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
