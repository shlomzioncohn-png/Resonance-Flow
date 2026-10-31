package com.rnd.chain.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.chain.dto.FlowHealthEntityType;
import com.rnd.chain.dto.FlowHealthFlag;
import com.rnd.chain.dto.FlowHealthPattern;
import com.rnd.chain.dto.FlowHealthSeverity;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.exception.GlobalExceptionHandler;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.service.FlowHealthFlagService;
import com.rnd.chain.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FlowHealthController.class)
@ContextConfiguration(classes = {FlowHealthController.class, GlobalExceptionHandler.class})
class FlowHealthControllerTest {

    private static final String URL = "/flow-health/flags";
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FlowHealthFlagService service;

    @MockBean
    private TenantContext tenantContext;

    @BeforeEach
    void setUp() {
        when(tenantContext.requireTenantId()).thenReturn(TENANT);
    }

    private static FlowHealthFlag flag() {
        return new FlowHealthFlag("SLOW_CHAIN:abc", FlowHealthPattern.SLOW_CHAIN, FlowHealthEntityType.CHAIN,
                List.of("t1", "t2"), FlowHealthSeverity.HIGH, 0.9, 1, "slow",
                new ObjectMapper().createObjectNode().put("days", 12), Instant.parse("2026-10-28T00:00:00Z"));
    }

    @Test
    void getFlags_returnsPageWithDefaultsAndNoFilters() throws Exception {
        when(service.getFlags(TENANT, null, null, 0, 20)).thenReturn(new PageResponse<>(List.of(flag()), 0, 20, 1, 1));

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].flagId").value("SLOW_CHAIN:abc"))
                .andExpect(jsonPath("$.content[0].pattern").value("SLOW_CHAIN"))
                .andExpect(jsonPath("$.content[0].entityType").value("chain"))
                .andExpect(jsonPath("$.content[0].severity").value("high"))
                .andExpect(jsonPath("$.content[0].entityIds[1]").value("t2"))
                .andExpect(jsonPath("$.content[0].evidence.days").value(12))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getFlags_passesFiltersAndPagingToTheService() throws Exception {
        when(service.getFlags(TENANT, FlowHealthPattern.CHRONIC_BLOCKER, FlowHealthSeverity.LOW, 2, 5))
                .thenReturn(new PageResponse<>(List.of(), 2, 5, 0, 0));

        mockMvc.perform(get(URL).param("pattern", "CHRONIC_BLOCKER").param("severity", "low")
                        .param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.page").value(2));
    }

    @Test
    void getFlags_missingTenantHeader_is400AndNeverReachesTheService() throws Exception {
        when(tenantContext.requireTenantId())
                .thenThrow(new InvalidRequestException("Missing required header: X-Tenant-Id"));

        mockMvc.perform(get(URL))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void getFlags_unknownSeverity_is400() throws Exception {
        mockMvc.perform(get(URL).param("severity", "extreme"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getFlags_unknownPattern_is400() throws Exception {
        mockMvc.perform(get(URL).param("pattern", "NOPE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getFlags_invalidPaging_is400() throws Exception {
        when(service.getFlags(TENANT, null, null, 0, 500)).thenThrow(new InvalidRequestException("bad paging"));

        mockMvc.perform(get(URL).param("size", "500"))
                .andExpect(status().isBadRequest());
    }
}
