package com.rnd.bridge.crm.controller;

import com.rnd.bridge.crm.CrmSourceResult;
import com.rnd.bridge.crm.CrmSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the HTTP layer only, with {@link CrmSyncService} mocked. {@code CrmSyncServiceTest}
 * already covers a real multi-source run; repeating that through HTTP would add nothing.
 */
@WebMvcTest(CrmBridgeController.class)
class CrmBridgeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CrmSyncService crmSyncService;

    @Test
    void postReturnsOkWithTheCountsFromOneSuccessfulSourceBackwardCompatFieldsUnchanged() throws Exception {
        when(crmSyncService.syncAll()).thenReturn(List.of(
                new CrmSourceResult("hubspot", 32, 32, 0, List.of(), 0, null)));

        mockMvc.perform(post("/bridge/crm/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(32))
                .andExpect(jsonPath("$.published").value(32))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.skippedAtRead").value(0))
                .andExpect(jsonPath("$.sources.length()").value(1))
                .andExpect(jsonPath("$.sources[0].source").value("hubspot"));
    }

    @Test
    void aRejectedRecordStillReturnsOkWithTheMessageAndNoStackTraceOrClassName() throws Exception {
        when(crmSyncService.syncAll()).thenReturn(List.of(
                new CrmSourceResult("hubspot", 32, 31, 1,
                        List.of("Invalid Task.title = null: must not be null"), 0, null)));

        mockMvc.perform(post("/bridge/crm/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0]").value("Invalid Task.title = null: must not be null"))
                .andExpect(content().string(not(containsString("ValidationException"))))
                .andExpect(content().string(not(containsString("stackTrace"))))
                .andExpect(content().string(not(containsString("com.rnd.bridge.core.error"))));
    }

    @Test
    void reportsSkippedAtReadSummedAcrossSources() throws Exception {
        when(crmSyncService.syncAll()).thenReturn(List.of(
                new CrmSourceResult("hubspot", 29, 29, 0, List.of(), 3, null),
                new CrmSourceResult("salesforce", 10, 10, 0, List.of(), 2, null)));

        mockMvc.perform(post("/bridge/crm/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skippedAtRead").value(5))
                .andExpect(jsonPath("$.read").value(39))
                .andExpect(jsonPath("$.published").value(39));
    }

    @Test
    void oneSourceFailingStillReturnsOkWithThatSourcesErrorInSources() throws Exception {
        when(crmSyncService.syncAll()).thenReturn(List.of(
                new CrmSourceResult("hubspot", 0, 0, 0, List.of(), 0, "HubSpot unreachable"),
                new CrmSourceResult("salesforce", 10, 10, 0, List.of(), 0, null)));

        mockMvc.perform(post("/bridge/crm/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(10))
                .andExpect(jsonPath("$.published").value(10))
                .andExpect(jsonPath("$.sources[0].source").value("hubspot"))
                .andExpect(jsonPath("$.sources[0].error").value("HubSpot unreachable"))
                .andExpect(jsonPath("$.sources[1].source").value("salesforce"))
                .andExpect(jsonPath("$.sources[1].error").value(nullValue()));
    }

    @Test
    void allSourcesFailingReturnsServiceUnavailableWithPerSourceErrorsInTheBody() throws Exception {
        when(crmSyncService.syncAll()).thenReturn(List.of(
                new CrmSourceResult("hubspot", 0, 0, 0, List.of(), 0, "HubSpot unreachable"),
                new CrmSourceResult("salesforce", 0, 0, 0, List.of(), 0, "Salesforce unreachable")));

        mockMvc.perform(post("/bridge/crm/sync"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.sources[0].error").value("HubSpot unreachable"))
                .andExpect(jsonPath("$.sources[1].error").value("Salesforce unreachable"));
    }

    @Test
    void getIsNotAllowed() throws Exception {
        mockMvc.perform(get("/bridge/crm/sync"))
                .andExpect(status().isMethodNotAllowed());
    }
}
