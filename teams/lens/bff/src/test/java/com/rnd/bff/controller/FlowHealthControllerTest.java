package com.rnd.bff.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rnd.bff.config.SecurityConfig;
import com.rnd.bff.dto.FlowHealthDTO;
import com.rnd.bff.dto.FlowHealthEntityDTO;
import com.rnd.bff.dto.FlowHealthFlagDTO;
import com.rnd.bff.dto.FlowHealthTaskRefDTO;
import com.rnd.bff.exception.ServiceUnavailableException;
import com.rnd.bff.service.FlowHealthService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

// @Import(SecurityConfig.class): see ManagerDashboardControllerTest.
@WebMvcTest(FlowHealthController.class)
@Import(SecurityConfig.class)
class FlowHealthControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private FlowHealthService flowHealthService;

    @Test
    void returnsFlagsWithNamedEntitiesAndForwardsTheActor() throws Exception {
        FlowHealthFlagDTO flag = FlowHealthFlagDTO.builder()
                .flagId("CHRONIC_BLOCKER:1").rank(1).pattern("CHRONIC_BLOCKER").entityType("task")
                .severity("high").score(0.9).explanation("blocks a lot")
                .entities(List.of(new FlowHealthEntityDTO("t1", "Set up DB",
                        List.of(new FlowHealthTaskRefDTO("t1", "Set up DB")))))
                .projects(List.of()).build();
        when(flowHealthService.getFlowHealth("mgr_001")).thenReturn(FlowHealthDTO.builder()
                .lastRunAt("2026-10-28T10:00:00Z")
                .counts(Map.of("CHRONIC_BLOCKER", 1, "RECURRING_CYCLE", 0, "SLOW_CHAIN", 0))
                .flags(List.of(flag)).taskNames(Map.of("t1", "Set up DB")).projects(List.of()).build());

        mockMvc.perform(get("/manager/flow-health").header("X-User-Id", "mgr_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastRunAt", is("2026-10-28T10:00:00Z")))
                .andExpect(jsonPath("$.counts.CHRONIC_BLOCKER", is(1)))
                .andExpect(jsonPath("$.flags[0].entities[0].label", is("Set up DB")))
                .andExpect(jsonPath("$.taskNames.t1", is("Set up DB")));
        verify(flowHealthService).getFlowHealth("mgr_001");
    }

    @Test
    void noFlags_keepsLastRunAtAsExplicitNull() throws Exception {
        when(flowHealthService.getFlowHealth("mgr_001")).thenReturn(FlowHealthDTO.builder()
                .counts(Map.of()).flags(List.of()).taskNames(Map.of()).projects(List.of()).build());

        mockMvc.perform(get("/manager/flow-health").header("X-User-Id", "mgr_001"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"lastRunAt\": null, \"flags\": []}"));
    }

    @Test
    void chainUnavailable_is503WithoutInternalDetails() throws Exception {
        when(flowHealthService.getFlowHealth("mgr_001"))
                .thenThrow(new ServiceUnavailableException("chain", "connect refused to chain-service:8081"));

        mockMvc.perform(get("/manager/flow-health").header("X-User-Id", "mgr_001"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode", is("SERVICE_UNAVAILABLE")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("chain-service:8081"))));
    }

    @Test
    void missingActorHeader_isRejected() throws Exception {
        mockMvc.perform(get("/manager/flow-health")).andExpect(status().is4xxClientError());
    }
}
