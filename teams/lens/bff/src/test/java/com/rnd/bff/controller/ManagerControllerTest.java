package com.rnd.bff.controller;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rnd.bff.client.ManagerClient;
import com.rnd.bff.config.CorsConfig;
import com.rnd.bff.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

// ManagerClient is a real bean here (not mocked) - it's a plain mock-data
// class with no actual network calls yet, so exercising it for real is
// cheap and verifies the actual response shape, not a stubbed stand-in.
// SecurityConfig/CorsConfig: @WebMvcTest doesn't load them, and without them
// Spring Security's default (401 on everything) applies - see WorkspaceControllerTest.
@WebMvcTest(ManagerController.class)
@Import({ManagerClient.class, SecurityConfig.class, CorsConfig.class})
class ManagerControllerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void getWorkload_returnsEmptyList() throws Exception {
        mockMvc
                .perform(get("/manager/workload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    void getProjects_returnsAllTwelveSeededProjects() throws Exception {
        mockMvc
                .perform(get("/manager/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(12)))
                .andExpect(jsonPath("$[0].id", is("p1")))
                .andExpect(jsonPath("$[0].name", is("Checkout Revamp")))
                .andExpect(jsonPath("$[0].members.length()", is(3)));
    }
}
