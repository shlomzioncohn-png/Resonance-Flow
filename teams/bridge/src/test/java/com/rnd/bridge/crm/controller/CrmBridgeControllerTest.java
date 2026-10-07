package com.rnd.bridge.crm.controller;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.crm.CrmLane;
import com.rnd.bridge.crm.repository.DealRepository;
import com.rnd.bridge.crm.repository.DealSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the HTTP layer only, with CrmLane and DealRepository mocked.
 * CrmLaneIntegrationTest already covers a real run over the 79-record
 * dataset; repeating that through HTTP would add nothing.
 */
@WebMvcTest(CrmBridgeController.class)
class CrmBridgeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CrmLane crmLane;

    @MockBean
    private DealRepository dealRepository;

    private static DealSnapshot snapshotAt(Instant asOf) {
        return new DealSnapshot(asOf, List.of(), Map.of(), List.of());
    }

    @Test
    void postReturnsOkWithTheCountsFromTheLaneResult() throws Exception {
        Instant asOf = Instant.parse("2026-08-24T09:00:00Z");
        when(dealRepository.snapshot()).thenReturn(snapshotAt(asOf));
        when(crmLane.run(any(Stream.class), eq(asOf)))
                .thenReturn(new IntakeResult(79, 79, 0, List.of()));

        mockMvc.perform(post("/bridge/crm/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(79))
                .andExpect(jsonPath("$.published").value(79))
                .andExpect(jsonPath("$.rejected").value(0));
    }

    @Test
    void aRejectedRecordStillReturnsOkWithTheMessageAndNoStackTraceOrClassName() throws Exception {
        Instant asOf = Instant.parse("2026-08-24T09:00:00Z");
        when(dealRepository.snapshot()).thenReturn(snapshotAt(asOf));
        when(crmLane.run(any(Stream.class), eq(asOf)))
                .thenReturn(new IntakeResult(79, 78, 1,
                        List.of(new ValidationException("Invalid Task.title = null: must not be null"))));

        mockMvc.perform(post("/bridge/crm/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0]").value("Invalid Task.title = null: must not be null"))
                .andExpect(content().string(not(containsString("ValidationException"))))
                .andExpect(content().string(not(containsString("stackTrace"))))
                .andExpect(content().string(not(containsString("com.rnd.bridge.core.error"))));
    }

    @Test
    void asOfComesFromTheSnapshotNotTheClock() throws Exception {
        Instant configuredAsOf = Instant.parse("2020-01-01T00:00:00Z");
        when(dealRepository.snapshot()).thenReturn(snapshotAt(configuredAsOf));
        when(crmLane.run(any(Stream.class), any())).thenReturn(new IntakeResult(0, 0, 0, List.of()));

        mockMvc.perform(post("/bridge/crm/sync"))
                .andExpect(status().isOk());

        verify(crmLane).run(any(Stream.class), eq(configuredAsOf));
    }

    @Test
    void getIsNotAllowed() throws Exception {
        mockMvc.perform(get("/bridge/crm/sync"))
                .andExpect(status().isMethodNotAllowed());
    }
}
