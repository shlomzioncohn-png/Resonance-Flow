package com.rnd.bff.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rnd.bff.config.SecurityConfig;
import com.rnd.bff.dto.ManagerKpisDTO;
import com.rnd.bff.service.ManagerKpisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

// @Import(SecurityConfig.class): without it this WebMvcTest slice falls back to
// Spring Security's default (401 on everything) - see ManagerUsersControllerTest.
@WebMvcTest(ManagerDashboardController.class)
@Import(SecurityConfig.class)
class ManagerDashboardControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ManagerKpisService managerKpisService;

    @Test
    void returnsTheFourKpisWithAvgBlockedDaysAsNull() throws Exception {
        when(managerKpisService.getKpis(eq("mgr_001"), isNull(), isNull()))
                .thenReturn(ManagerKpisDTO.builder().inProgress(42).blocked(9).delayed(5).build());

        mockMvc.perform(get("/manager/dashboard/kpis").header("X-User-Id", "mgr_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inProgress", is(42)))
                .andExpect(jsonPath("$.blocked", is(9)))
                .andExpect(jsonPath("$.delayed", is(5)))
                // Present and null (not left out) - the frontend shows "Not available yet" for it.
                .andExpect(content().json("{\"avgBlockedDays\": null}"));
    }

    @Test
    void forwardsPeriodAndProject() throws Exception {
        when(managerKpisService.getKpis(any(), any(), any())).thenReturn(new ManagerKpisDTO());

        mockMvc.perform(get("/manager/dashboard/kpis")
                        .param("period", "30d")
                        .param("project", "p-1")
                        .header("X-User-Id", "mgr_001"))
                .andExpect(status().isOk());

        verify(managerKpisService).getKpis("mgr_001", "30d", "p-1");
    }

    @Test
    void unknownPeriodIs400() throws Exception {
        when(managerKpisService.getKpis(any(), eq("1y"), any()))
                .thenThrow(new IllegalArgumentException("period must be one of 7d, 30d, 90d"));

        mockMvc.perform(get("/manager/dashboard/kpis").param("period", "1y").header("X-User-Id", "mgr_001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingUserIdIs400() throws Exception {
        mockMvc.perform(get("/manager/dashboard/kpis")).andExpect(status().isBadRequest());
    }
}
