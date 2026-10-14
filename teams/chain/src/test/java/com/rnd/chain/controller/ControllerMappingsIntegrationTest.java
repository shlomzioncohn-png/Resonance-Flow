package com.rnd.chain.controller;

import com.rnd.chain.service.DependencyGraphService;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.chain.service.TaskTraversalService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = ControllerMappingsIntegrationTest.TestApp.class
)
@AutoConfigureMockMvc
class ControllerMappingsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskTraversalService taskTraversalService;

    @MockBean
    private TaskDependencyService taskDependencyService;

    @MockBean
    private DependencyGraphService dependencyGraphService;

    @Test
    void previouslyDuplicatedReadPaths_areMappedUnambiguously() throws Exception {

        when(taskTraversalService.getDirectDependencies(any(UUID.class)))
                .thenReturn(List.of());

        when(taskTraversalService.getDirectDependents(any(UUID.class)))
                .thenReturn(List.of());

        when(taskTraversalService.getAncestors(any(UUID.class)))
                .thenReturn(List.of());

        when(taskTraversalService.getAllDescendants(any(UUID.class)))
                .thenReturn(List.of());

        UUID taskId = UUID.randomUUID();

        mockMvc.perform(get("/tasks/{taskId}/dependencies", taskId))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mockMvc.perform(get("/tasks/{taskId}/dependents", taskId))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mockMvc.perform(get("/tasks/{taskId}/ancestors", taskId))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mockMvc.perform(get("/tasks/{taskId}/descendants", taskId))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {
                    DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    JpaRepositoriesAutoConfiguration.class,
                    FlywayAutoConfiguration.class
            }
    )
    @Import({
            ChainController.class,
            TaskDependencyController.class
    })
    static class TestApp {
    }
}