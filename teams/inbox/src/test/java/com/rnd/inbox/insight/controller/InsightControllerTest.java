package com.rnd.inbox.insight.controller;

import com.rnd.inbox.insight.dto.InsightCreateRequestDto;
import com.rnd.inbox.insight.dto.InsightResponseDto;
import com.rnd.inbox.insight.exception.InvalidInsightPeriodException;
import com.rnd.inbox.insight.model.InsightType;
import com.rnd.inbox.insight.service.InsightService;
import com.rnd.inbox.shared.config.AppConfig;
import com.rnd.inbox.shared.config.WebMvcConfig;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.model.UserStatus;
import com.rnd.inbox.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// HTTP layer only - the service is mocked. Status codes and snake_case field names follow the rest of the Inbox API.
@WebMvcTest(InsightController.class)
@Import({AppConfig.class, WebMvcConfig.class})
class InsightControllerTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    private static final String VALID_CREATE_BODY = """
            {
              "insight_type": "REINFORCEMENT",
              "insight_text": "Almost every task this month was completed before its deadline.",
              "recommendation_text": null,
              "evidence_text": "12 of 13 tasks were completed before the deadline.",
              "period_from": "2026-09-07",
              "period_to": "2026-10-06"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InsightService insightService;

    @MockBean
    private UserRepository userRepository;

    private UUID actorId;
    private UUID insightId;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        insightId = UUID.randomUUID();
        User actor = new User();
        actor.setId(actorId);
        actor.setRole(UserRole.EMPLOYEE);
        actor.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
    }

    private InsightResponseDto sampleInsight() {
        return new InsightResponseDto(insightId, InsightType.REINFORCEMENT,
                "Almost every task this month was completed before its deadline.",
                null,
                "12 of 13 tasks were completed before the deadline.",
                LocalDate.parse("2026-09-07"), LocalDate.parse("2026-10-06"),
                Instant.parse("2026-10-18T09:00:00Z"));
    }

    private String bodyWith(String field, String jsonValue) {
        return VALID_CREATE_BODY.replaceFirst("\"" + field + "\": [^,\\n]+", "\"" + field + "\": " + jsonValue);
    }

    // ---- POST /api/v1/insights ----

    @Test
    void createInsight_withValidRequest_returns201WithSnakeCaseBody() throws Exception {
        when(insightService.createInsight(any(), eq(actorId))).thenReturn(sampleInsight());

        mockMvc.perform(post("/api/v1/insights")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(insightId.toString()))
                .andExpect(jsonPath("$.insight_type").value("REINFORCEMENT"))
                .andExpect(jsonPath("$.insight_text")
                        .value("Almost every task this month was completed before its deadline."))
                .andExpect(jsonPath("$.recommendation_text").doesNotExist())
                .andExpect(jsonPath("$.evidence_text").value("12 of 13 tasks were completed before the deadline."))
                .andExpect(jsonPath("$.period_from").value("2026-09-07"))
                .andExpect(jsonPath("$.period_to").value("2026-10-06"))
                .andExpect(jsonPath("$.created_at").value("2026-10-18T09:00:00Z"));
    }

    @Test
    void createInsight_passesTheRequestBodyFieldsToTheService() throws Exception {
        when(insightService.createInsight(any(), eq(actorId))).thenReturn(sampleInsight());
        ArgumentCaptor<InsightCreateRequestDto> request = ArgumentCaptor.forClass(InsightCreateRequestDto.class);

        mockMvc.perform(post("/api/v1/insights")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith("recommendation_text", "\"Keep it up.\"")))
                .andExpect(status().isCreated());

        verify(insightService).createInsight(request.capture(), eq(actorId));
        assertThat(request.getValue().getInsightType()).isEqualTo("REINFORCEMENT");
        assertThat(request.getValue().getRecommendationText()).isEqualTo("Keep it up.");
        assertThat(request.getValue().getPeriodFrom()).isEqualTo(LocalDate.parse("2026-09-07"));
        assertThat(request.getValue().getPeriodTo()).isEqualTo(LocalDate.parse("2026-10-06"));
    }

    @Test
    void createInsight_withMissingRequiredFields_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/insights")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.field_errors[?(@.field == 'insight_type')]").exists())
                .andExpect(jsonPath("$.field_errors[?(@.field == 'insight_text')]").exists())
                .andExpect(jsonPath("$.field_errors[?(@.field == 'evidence_text')]").exists())
                .andExpect(jsonPath("$.field_errors[?(@.field == 'period_from')]").exists())
                .andExpect(jsonPath("$.field_errors[?(@.field == 'period_to')]").exists())
                .andExpect(jsonPath("$.field_errors[?(@.field == 'recommendation_text')]").doesNotExist());

        verify(insightService, never()).createInsight(any(), any());
    }

    @Test
    void createInsight_withUnknownInsightType_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/insights")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith("insight_type", "\"PRAISE\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.field_errors[0].field").value("insight_type"));

        verify(insightService, never()).createInsight(any(), any());
    }

    @Test
    void createInsight_withInsightTypeNone_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/insights")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith("insight_type", "\"NONE\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field_errors[0].field").value("insight_type"));

        verify(insightService, never()).createInsight(any(), any());
    }

    @Test
    void createInsight_withBlankEvidenceText_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/insights")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith("evidence_text", "\"   \"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field_errors[0].field").value("evidence_text"));
    }

    @Test
    void createInsight_withInsightTextLongerThan1000Characters_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/insights")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith("insight_text", "\"" + "x".repeat(1001) + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field_errors[0].field").value("insight_text"));
    }

    @Test
    void createInsight_withRecommendationTextLongerThan1000Characters_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/insights")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWith("recommendation_text", "\"" + "x".repeat(1001) + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field_errors[0].field").value("recommendation_text"));
    }

    @Test
    void createInsight_whenPeriodToBeforePeriodFrom_returns400WithInvalidInsightPeriod() throws Exception {
        when(insightService.createInsight(any(), eq(actorId))).thenThrow(
                new InvalidInsightPeriodException(LocalDate.parse("2026-10-06"), LocalDate.parse("2026-09-07")));

        mockMvc.perform(post("/api/v1/insights")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_INSIGHT_PERIOD"));
    }

    @Test
    void createInsight_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/insights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));

        verify(insightService, never()).createInsight(any(), any());
    }

    // The header is only parsed here; whether the user exists is the service's check (InsightServiceTest).
    @Test
    void createInsight_whenServiceRejectsUnknownActor_returns401() throws Exception {
        UUID unknownActorId = UUID.randomUUID();
        when(insightService.createInsight(any(), eq(unknownActorId)))
                .thenThrow(new UnauthenticatedException("Actor " + unknownActorId + " does not exist"));

        mockMvc.perform(post("/api/v1/insights")
                        .header(ACTOR_HEADER, unknownActorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void createInsight_withMalformedActorHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/insights")
                        .header(ACTOR_HEADER, "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isUnauthorized());

        verify(insightService, never()).createInsight(any(), any());
    }

    // ---- GET /api/v1/insights ----

    @Test
    void listMyInsights_returns200WithFlatPageResponse() throws Exception {
        when(insightService.listMyInsights(any(), eq(actorId)))
                .thenReturn(new PageResponseDto<>(List.of(sampleInsight()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/insights").header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(insightId.toString()))
                .andExpect(jsonPath("$.content[0].insight_type").value("REINFORCEMENT"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total_elements").value(1))
                .andExpect(jsonPath("$.total_pages").value(1));
    }

    @Test
    void listMyInsights_passesRequestedPageAndSizeToTheService() throws Exception {
        when(insightService.listMyInsights(any(), eq(actorId)))
                .thenReturn(new PageResponseDto<>(List.of(), 2, 5, 0, 0));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        mockMvc.perform(get("/api/v1/insights?page=2&size=5").header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk());

        verify(insightService).listMyInsights(pageable.capture(), eq(actorId));
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void listMyInsights_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/insights"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));

        verify(insightService, never()).listMyInsights(any(), any());
    }
}
