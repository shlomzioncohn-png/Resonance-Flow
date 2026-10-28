package com.rnd.bff.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rnd.bff.client.ManagerClient;
import com.rnd.bff.config.CorsConfig;
import com.rnd.bff.config.SecurityConfig;
import com.rnd.bff.dto.ManagerProjectDTO;
import com.rnd.bff.service.ManagerKpisService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

// ManagerClient is a real bean here (not mocked) - it's a plain mock-data
// class with no actual network calls yet. The projects come from
// ManagerKpisService (Inbox's tasks), mocked here.
// SecurityConfig/CorsConfig: @WebMvcTest doesn't load them, and without them
// Spring Security's default (401 on everything) applies - see WorkspaceControllerTest.
@WebMvcTest(ManagerController.class)
@Import({ManagerClient.class, SecurityConfig.class, CorsConfig.class})
class ManagerControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ManagerKpisService managerKpisService;

    @Test
    void getWorkload_returnsEmptyList() throws Exception {
        mockMvc
                .perform(get("/manager/workload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(0)));
    }

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
}
