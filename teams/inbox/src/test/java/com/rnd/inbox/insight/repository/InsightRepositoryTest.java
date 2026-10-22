package com.rnd.inbox.insight.repository;

import com.rnd.inbox.insight.model.Insight;
import com.rnd.inbox.insight.model.InsightType;
import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.model.TenantStatus;
import com.rnd.inbox.tenant.repository.TenantRepository;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.model.UserStatus;
import com.rnd.inbox.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Runs against the real database, so it also proves the V16 migration: the insight_type enum mapping,
// the nullable recommendation, the period CHECK, and that a user's list never contains another user's insights.
class InsightRepositoryTest extends PostgresIntegrationTest {

    private static final LocalDate PERIOD_FROM = LocalDate.parse("2026-09-07");
    private static final LocalDate PERIOD_TO = LocalDate.parse("2026-10-06");
    private static final PageRequest NEWEST_FIRST_PAGE =
            PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

    @Autowired
    private InsightRepository insightRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    private final List<UUID> createdTenantIds = new ArrayList<>();
    private Tenant tenant;
    private User employee;

    @BeforeEach
    void setUp() {
        tenant = persistTenant();
        employee = persistUser(tenant);
    }

    @AfterEach
    void cleanUp() {
        deleteTenantData(createdTenantIds);
    }

    private Tenant persistTenant() {
        Tenant t = new Tenant();
        t.setName("Acme");
        t.setSlug("acme-" + UUID.randomUUID());
        t.setStatus(TenantStatus.ACTIVE);
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        Tenant saved = tenantRepository.saveAndFlush(t);
        createdTenantIds.add(saved.getId());
        return saved;
    }

    private User persistUser(Tenant tenant) {
        User user = new User();
        user.setTenant(tenant);
        user.setDisplayName("User " + UUID.randomUUID());
        user.setRole(UserRole.EMPLOYEE);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.saveAndFlush(user);
    }

    private Insight newInsight(User user, InsightType type, Instant createdAt) {
        Insight insight = new Insight();
        insight.setTenant(user.getTenant());
        insight.setUser(user);
        insight.setInsightType(type);
        insight.setInsightText("Insight sentence");
        insight.setEvidenceText("12 of 13 tasks were completed before the deadline.");
        insight.setPeriodFrom(PERIOD_FROM);
        insight.setPeriodTo(PERIOD_TO);
        insight.setCreatedAt(createdAt);
        return insight;
    }

    private Insight persistInsight(User user, InsightType type, Instant createdAt) {
        return insightRepository.saveAndFlush(newInsight(user, type, createdAt));
    }

    @Test
    void saveAndRead_roundTripsEveryInsightTypeAndANullRecommendation() {
        for (InsightType type : InsightType.values()) {
            persistInsight(employee, type, Instant.now());
        }

        Page<Insight> page = insightRepository.findByUserId(employee.getId(), NEWEST_FIRST_PAGE);

        assertThat(page.getContent()).extracting(Insight::getInsightType)
                .containsExactlyInAnyOrder(InsightType.values());
        assertThat(page.getContent()).allSatisfy(insight -> {
            assertThat(insight.getRecommendationText()).isNull();
            assertThat(insight.getPeriodFrom()).isEqualTo(PERIOD_FROM);
            assertThat(insight.getPeriodTo()).isEqualTo(PERIOD_TO);
        });
    }

    @Test
    void findByUserId_returnsOnlyThatUsersInsights() {
        User colleague = persistUser(tenant);
        Insight mine = persistInsight(employee, InsightType.TREND, Instant.now());
        persistInsight(colleague, InsightType.REINFORCEMENT, Instant.now());

        Page<Insight> page = insightRepository.findByUserId(employee.getId(), NEWEST_FIRST_PAGE);

        assertThat(page.getContent()).extracting(Insight::getId).containsExactly(mine.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findByUserId_ordersNewestFirst() {
        Insight oldest = persistInsight(employee, InsightType.REINFORCEMENT, Instant.parse("2026-08-01T09:00:00Z"));
        Insight newest = persistInsight(employee, InsightType.TREND, Instant.parse("2026-10-17T09:00:00Z"));
        Insight middle = persistInsight(employee, InsightType.IMPROVEMENT_OPPORTUNITY,
                Instant.parse("2026-09-15T09:00:00Z"));

        Page<Insight> page = insightRepository.findByUserId(employee.getId(), NEWEST_FIRST_PAGE);

        assertThat(page.getContent()).extracting(Insight::getId)
                .containsExactly(newest.getId(), middle.getId(), oldest.getId());
    }

    @Test
    void findByUserId_forUserWithoutInsights_returnsEmptyPage() {
        Page<Insight> page = insightRepository.findByUserId(employee.getId(), NEWEST_FIRST_PAGE);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void save_whenPeriodToBeforePeriodFrom_isRejectedByTheDatabase() {
        Insight insight = newInsight(employee, InsightType.TREND, Instant.now());
        insight.setPeriodFrom(PERIOD_TO);
        insight.setPeriodTo(PERIOD_FROM);

        assertThatThrownBy(() -> insightRepository.saveAndFlush(insight))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_withoutEvidenceText_isRejectedByTheDatabase() {
        Insight insight = newInsight(employee, InsightType.TREND, Instant.now());
        insight.setEvidenceText(null);

        assertThatThrownBy(() -> insightRepository.saveAndFlush(insight))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
