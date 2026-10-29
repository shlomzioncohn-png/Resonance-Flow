package com.rnd.bff.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rnd.bff.config.CorsConfig;
import com.rnd.bff.config.SecurityConfig;
import com.rnd.bff.dto.ConstraintDTO;
import com.rnd.bff.dto.DependencyGraphDTO;
import com.rnd.bff.dto.ManagerProjectDTO;
import com.rnd.bff.exception.ConstraintNotFoundException;
import com.rnd.bff.exception.ServiceUnavailableException;
import com.rnd.bff.exception.TaskNotFoundException;
import com.rnd.bff.service.BottlenecksService;
import com.rnd.bff.service.ManagerKpisService;
import com.rnd.bff.service.TaskConstraintsService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// The projects come from ManagerKpisService (Inbox's tasks) and the graph from
// BottlenecksService (Chain + Inbox), both mocked here.
// SecurityConfig/CorsConfig: @WebMvcTest doesn't load them, and without them
// Spring Security's default (401 on everything) applies - see WorkspaceControllerTest.
@WebMvcTest(ManagerController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class ManagerControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ManagerKpisService managerKpisService;
    @MockBean private BottlenecksService bottlenecksService;
    @MockBean private TaskConstraintsService taskConstraintsService;

    @Test
    void getProjects_returnsTheActorsProjects() throws Exception {
        when(managerKpisService.getProjects("mgr_001")).thenReturn(List.of(
                ManagerProjectDTO.builder().id("p-1").name("Checkout Revamp").members(List.of()).build()));

        mockMvc
                .perform(get("/manager/projects").header("X-User-Id", "mgr_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].name", is("Checkout Revamp")));
    }

    @Test
    void getProjects_withoutUser_returns400() throws Exception {
        mockMvc.perform(get("/manager/projects")).andExpect(status().isBadRequest());
    }

    @Test
    void getDependencyGraph_returnsTheGraphInTheFrontendsShape() throws Exception {
        when(bottlenecksService.getGraph("mgr_001", "p-1")).thenReturn(new DependencyGraphDTO(
                List.of(new DependencyGraphDTO.Node("t-1", "Set up CI", "ACTIVE", false, 2),
                        new DependencyGraphDTO.Node("t-2", "Deploy", "PENDING", true, 0)),
                List.of(new DependencyGraphDTO.Edge("t-1", "t-2", 1.0))));

        mockMvc
                .perform(get("/manager/bottlenecks/graph").param("projectId", "p-1").header("X-User-Id", "mgr_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()", is(2)))
                .andExpect(jsonPath("$.nodes[0].title", is("Set up CI")))
                .andExpect(jsonPath("$.nodes[0].blocksCount", is(2)))
                .andExpect(jsonPath("$.nodes[1].isBlocked", is(true)))
                .andExpect(jsonPath("$.edges[0].source", is("t-1")))
                .andExpect(jsonPath("$.edges[0].target", is("t-2")))
                .andExpect(jsonPath("$.edges[0].confidence", is(1.0)));
    }

    @Test
    void getDependencyGraph_withoutProject_asksForTheWholeTenant() throws Exception {
        when(bottlenecksService.getGraph("mgr_001", null)).thenReturn(new DependencyGraphDTO(List.of(), List.of()));

        mockMvc
                .perform(get("/manager/bottlenecks/graph").header("X-User-Id", "mgr_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()", is(0)));
    }

    @Test
    void getDependencyGraph_withoutUser_returns400() throws Exception {
        mockMvc.perform(get("/manager/bottlenecks/graph")).andExpect(status().isBadRequest());
    }

    @Test
    void getTaskConstraints_returnsTheTasksConstraints() throws Exception {
        when(taskConstraintsService.getConstraints("mgr_001", "t-1")).thenReturn(List.of(
                ConstraintDTO.builder().constraintId("c1").type("APPROVAL_NEEDED").status("PENDING")
                        .sourceRef("mind:run-42").build()));

        mockMvc
                .perform(get("/manager/tasks/t-1/constraints").header("X-User-Id", "mgr_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].constraintId", is("c1")))
                .andExpect(jsonPath("$[0].sourceRef", is("mind:run-42")));
    }

    @Test
    void getTaskConstraints_taskNotInTheTenant_returns404() throws Exception {
        when(taskConstraintsService.getConstraints("mgr_001", "t-x")).thenThrow(new TaskNotFoundException("t-x"));

        mockMvc
                .perform(get("/manager/tasks/t-x/constraints").header("X-User-Id", "mgr_001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("NOT_FOUND")));
    }

    @Test
    void updateConstraintStatus_returnsTheUpdatedConstraint() throws Exception {
        when(taskConstraintsService.updateStatus("mgr_001", "t-1", "c1", "SATISFIED")).thenReturn(
                ConstraintDTO.builder().constraintId("c1").type("APPROVAL_NEEDED").status("SATISFIED").build());

        mockMvc
                .perform(patch("/manager/tasks/t-1/constraints/c1").header("X-User-Id", "mgr_001")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SATISFIED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SATISFIED")));
    }

    @Test
    void updateConstraintStatus_invalidStatus_returns400WithoutCallingChain() throws Exception {
        mockMvc
                .perform(patch("/manager/tasks/t-1/constraints/c1").header("X-User-Id", "mgr_001")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("BAD_REQUEST")));

        verifyNoInteractions(taskConstraintsService);
    }

    @Test
    void updateConstraintStatus_missingConstraint_returns404() throws Exception {
        when(taskConstraintsService.updateStatus("mgr_001", "t-1", "missing", "WAIVED"))
                .thenThrow(new ConstraintNotFoundException("t-1", "missing"));

        mockMvc
                .perform(patch("/manager/tasks/t-1/constraints/missing").header("X-User-Id", "mgr_001")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"WAIVED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Constraint not found")));
    }

    @Test
    void updateConstraintStatus_chainDown_returns503() throws Exception {
        when(taskConstraintsService.updateStatus("mgr_001", "t-1", "c1", "PENDING"))
                .thenThrow(new ServiceUnavailableException("chain", "Couldn't update the constraint's status"));

        mockMvc
                .perform(patch("/manager/tasks/t-1/constraints/c1").header("X-User-Id", "mgr_001")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.details", is("Service: chain")));
    }
}
