package com.rnd.bff.controller;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rnd.bff.config.CorsConfig;
import com.rnd.bff.config.SecurityConfig;
import com.rnd.bff.service.ProjectDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

// SecurityConfig/CorsConfig: @WebMvcTest doesn't load them - see WorkspaceControllerTest.
@WebMvcTest(ProjectDetailsController.class)
@Import({ProjectDetailsService.class, SecurityConfig.class, CorsConfig.class})
class ProjectDetailsControllerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void getProjectDetails_knownProject_returnsDetails() throws Exception {
        mockMvc
                .perform(get("/admin/projects/p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId", is("p1")));
    }

    @Test
    void getProjectDetails_unknownProject_returns404NotInternalError() throws Exception {
        mockMvc
                .perform(get("/admin/projects/p9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("NOT_FOUND")));
    }
}
