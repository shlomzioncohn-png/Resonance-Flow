package com.rnd.inbox.insight.service;

import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.insight.dto.InsightCreateRequestDto;
import com.rnd.inbox.insight.dto.InsightResponseDto;
import com.rnd.inbox.insight.exception.InvalidInsightPeriodException;
import com.rnd.inbox.insight.mapper.InsightMapper;
import com.rnd.inbox.insight.model.Insight;
import com.rnd.inbox.insight.model.InsightType;
import com.rnd.inbox.insight.repository.InsightRepository;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Expected behavior comes from issue #683 and the INSIGHTS section of the schema spec (§3.12):
// an insight belongs to the calling user and their tenant, is visible to that user only, and is listed newest first.
@ExtendWith(MockitoExtension.class)
class InsightServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final LocalDate PERIOD_FROM = LocalDate.parse("2026-09-07");
    private static final LocalDate PERIOD_TO = LocalDate.parse("2026-10-06");

    @Mock
    private InsightRepository insightRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditService auditService;

    private InsightService insightService;

    private Tenant tenant;
    private User actor;

    @BeforeEach
    void setUp() {
        insightService = new InsightService(insightRepository, userRepository,
                new InsightMapper(Clock.fixed(NOW, ZoneOffset.UTC)), auditService);

        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Acme");

        actor = new User();
        actor.setId(UUID.randomUUID());
        actor.setTenant(tenant);
        actor.setRole(UserRole.EMPLOYEE);
        // lenient: the unknown-actor tests look up a different id.
        lenient().when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        lenient().when(insightRepository.save(any(Insight.class))).thenAnswer(invocation -> {
            Insight insight = invocation.getArgument(0);
            insight.setId(UUID.randomUUID());
            return insight;
        });
    }

    private static InsightCreateRequestDto validRequest() {
        InsightCreateRequestDto request = new InsightCreateRequestDto();
        request.setInsightType("IMPROVEMENT_OPPORTUNITY");
        request.setInsightText("Short tasks finish on time, while long tasks ran late in both months.");
        request.setRecommendationText("Consider splitting long tasks into smaller steps.");
        request.setEvidenceText("4 of 4 long tasks finished late this month, 3 of 3 the month before.");
        request.setPeriodFrom(PERIOD_FROM);
        request.setPeriodTo(PERIOD_TO);
        return request;
    }

    private Insight insightOf(User user, Instant createdAt) {
        Insight insight = new Insight();
        insight.setId(UUID.randomUUID());
        insight.setTenant(user.getTenant());
        insight.setUser(user);
        insight.setInsightType(InsightType.REINFORCEMENT);
        insight.setInsightText("Almost every task this month was completed before its deadline.");
        insight.setEvidenceText("12 of 13 tasks were completed before the deadline.");
        insight.setPeriodFrom(PERIOD_FROM);
        insight.setPeriodTo(PERIOD_TO);
        insight.setCreatedAt(createdAt);
        return insight;
    }

    // ---- createInsight ----

    @Test
    void createInsight_withValidRequest_savesInsightForActorInActorsTenant() {
        ArgumentCaptor<Insight> saved = ArgumentCaptor.forClass(Insight.class);

        InsightResponseDto response = insightService.createInsight(validRequest(), actor.getId());

        verify(insightRepository).save(saved.capture());
        Insight insight = saved.getValue();
        assertThat(insight.getUser()).isSameAs(actor);
        assertThat(insight.getTenant()).isSameAs(tenant);
        assertThat(insight.getInsightType()).isEqualTo(InsightType.IMPROVEMENT_OPPORTUNITY);
        assertThat(insight.getInsightText())
                .isEqualTo("Short tasks finish on time, while long tasks ran late in both months.");
        assertThat(insight.getRecommendationText()).isEqualTo("Consider splitting long tasks into smaller steps.");
        assertThat(insight.getEvidenceText())
                .isEqualTo("4 of 4 long tasks finished late this month, 3 of 3 the month before.");
        assertThat(insight.getPeriodFrom()).isEqualTo(PERIOD_FROM);
        assertThat(insight.getPeriodTo()).isEqualTo(PERIOD_TO);
        assertThat(insight.getCreatedAt()).isEqualTo(NOW);

        assertThat(response.getId()).isEqualTo(insight.getId());
        assertThat(response.getInsightType()).isEqualTo(InsightType.IMPROVEMENT_OPPORTUNITY);
        assertThat(response.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void createInsight_withoutRecommendation_savesNullRecommendation() {
        InsightCreateRequestDto request = validRequest();
        request.setRecommendationText(null);
        ArgumentCaptor<Insight> saved = ArgumentCaptor.forClass(Insight.class);

        InsightResponseDto response = insightService.createInsight(request, actor.getId());

        verify(insightRepository).save(saved.capture());
        assertThat(saved.getValue().getRecommendationText()).isNull();
        assertThat(response.getRecommendationText()).isNull();
    }

    @Test
    void createInsight_withBlankRecommendation_savesNullRecommendation() {
        InsightCreateRequestDto request = validRequest();
        request.setRecommendationText("   ");
        ArgumentCaptor<Insight> saved = ArgumentCaptor.forClass(Insight.class);

        insightService.createInsight(request, actor.getId());

        verify(insightRepository).save(saved.capture());
        assertThat(saved.getValue().getRecommendationText()).isNull();
    }

    @Test
    void createInsight_whenPeriodToBeforePeriodFrom_throwsInvalidInsightPeriodExceptionAndSavesNothing() {
        InsightCreateRequestDto request = validRequest();
        request.setPeriodFrom(LocalDate.parse("2026-10-06"));
        request.setPeriodTo(LocalDate.parse("2026-09-07"));

        assertThatThrownBy(() -> insightService.createInsight(request, actor.getId()))
                .isInstanceOf(InvalidInsightPeriodException.class)
                .satisfies(e -> assertThat(((InvalidInsightPeriodException) e).getErrorCode())
                        .isEqualTo("INVALID_INSIGHT_PERIOD"));

        verify(insightRepository, never()).save(any());
        verify(auditService, never()).createAuditLog(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createInsight_withSingleDayPeriod_isAccepted() {
        InsightCreateRequestDto request = validRequest();
        request.setPeriodFrom(PERIOD_TO);
        request.setPeriodTo(PERIOD_TO);

        insightService.createInsight(request, actor.getId());

        verify(insightRepository).save(any(Insight.class));
    }

    @Test
    void createInsight_whenActorDoesNotExist_throwsUnauthenticatedAndSavesNothing() {
        UUID unknownActorId = UUID.randomUUID();
        when(userRepository.findById(unknownActorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> insightService.createInsight(validRequest(), unknownActorId))
                .isInstanceOf(UnauthenticatedException.class);

        verify(insightRepository, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void createInsight_writesInsightCreatedAuditRowWithoutTheInsightTexts() {
        ArgumentCaptor<Map<String, Object>> snapshot = ArgumentCaptor.forClass(Map.class);

        InsightResponseDto response = insightService.createInsight(validRequest(), actor.getId());

        verify(auditService).createAuditLog(eq("INSIGHT_CREATED"), eq("Insight"), eq(response.getId()),
                eq(tenant), eq(actor), snapshot.capture(), isNull());
        assertThat(snapshot.getValue())
                .containsEntry("userId", actor.getId())
                .containsEntry("insightType", "IMPROVEMENT_OPPORTUNITY")
                .containsEntry("periodFrom", "2026-09-07")
                .containsEntry("periodTo", "2026-10-06")
                .doesNotContainKeys("insightText", "recommendationText", "evidenceText");
    }

    // ---- listMyInsights ----

    @Test
    void listMyInsights_returnsOnlyTheActorsInsightsNewestFirst() {
        Insight newer = insightOf(actor, Instant.parse("2026-10-17T09:00:00Z"));
        Insight older = insightOf(actor, Instant.parse("2026-10-01T09:00:00Z"));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(insightRepository.findByUserId(eq(actor.getId()), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(newer, older), PageRequest.of(0, 20), 2));

        PageResponseDto<InsightResponseDto> page = insightService.listMyInsights(PageRequest.of(0, 20), actor.getId());

        verify(insightRepository).findByUserId(eq(actor.getId()), pageable.capture());
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        assertThat(page.getContent()).extracting(InsightResponseDto::getId)
                .containsExactly(newer.getId(), older.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void listMyInsights_keepsRequestedPageAndSizeButIgnoresRequestedSort() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(insightRepository.findByUserId(eq(actor.getId()), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

        insightService.listMyInsights(PageRequest.of(2, 5, Sort.by("insightText")), actor.getId());

        verify(insightRepository).findByUserId(eq(actor.getId()), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    @Test
    void listMyInsights_whenActorDoesNotExist_throwsUnauthenticated() {
        UUID unknownActorId = UUID.randomUUID();
        when(userRepository.findById(unknownActorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> insightService.listMyInsights(PageRequest.of(0, 20), unknownActorId))
                .isInstanceOf(UnauthenticatedException.class);

        verify(insightRepository, never()).findByUserId(any(), any());
    }
}
