package com.rnd.inbox.insight.service;

import com.rnd.inbox.audit.AuditService;
import com.rnd.inbox.insight.dto.InsightCreateRequestDto;
import com.rnd.inbox.insight.dto.InsightResponseDto;
import com.rnd.inbox.insight.exception.InvalidInsightPeriodException;
import com.rnd.inbox.insight.mapper.InsightMapper;
import com.rnd.inbox.insight.model.Insight;
import com.rnd.inbox.insight.repository.InsightRepository;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.UnauthenticatedException;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// Insights are personal: every insight is created for the calling user and only that user can read it -
// there is no role (team lead, admin) that sees someone else's insights.
@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    // Newest first; id breaks ties between insights created at the same instant (idx_insights_user_created_at_id).
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final InsightRepository insightRepository;
    private final UserRepository userRepository;
    private final InsightMapper insightMapper;
    private final AuditService auditService;

    public InsightService(InsightRepository insightRepository, UserRepository userRepository,
                          InsightMapper insightMapper, AuditService auditService) {
        this.insightRepository = insightRepository;
        this.userRepository = userRepository;
        this.insightMapper = insightMapper;
        this.auditService = auditService;
    }

    @Transactional
    public InsightResponseDto createInsight(InsightCreateRequestDto request, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        assertPeriodInOrder(request.getPeriodFrom(), request.getPeriodTo());

        Insight saved = insightRepository.save(insightMapper.toEntity(request, actor));

        auditService.createAuditLog("INSIGHT_CREATED", "Insight", saved.getId(), saved.getTenant(), actor,
                auditSnapshot(saved), null);
        log.info("Insight created: insightId={}, userId={}, insightType={}",
                saved.getId(), actor.getId(), saved.getInsightType());
        return insightMapper.toResponseDto(saved);
    }

    // The client chooses the page and its size, never the order - the list is always newest first.
    @Transactional(readOnly = true)
    public PageResponseDto<InsightResponseDto> listMyInsights(Pageable pageable, UUID actorId) {
        User actor = findActorOrThrow(actorId);
        Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST);
        return PageResponseDto.from(
                insightRepository.findByUserId(actor.getId(), newestFirst).map(insightMapper::toResponseDto));
    }

    private void assertPeriodInOrder(LocalDate periodFrom, LocalDate periodTo) {
        if (periodTo.isBefore(periodFrom)) {
            throw new InvalidInsightPeriodException(periodFrom, periodTo);
        }
    }

    private User findActorOrThrow(UUID actorId) {
        return userRepository.findById(actorId)
                .orElseThrow(() -> new UnauthenticatedException("Actor " + actorId + " does not exist"));
    }

    // Type and period only - the texts are personal feedback meant for the employee alone, so they are
    // not copied into the audit trail.
    private Map<String, Object> auditSnapshot(Insight insight) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("userId", insight.getUser().getId());
        snapshot.put("insightType", insight.getInsightType().name());
        snapshot.put("periodFrom", insight.getPeriodFrom().toString());
        snapshot.put("periodTo", insight.getPeriodTo().toString());
        return snapshot;
    }
}
