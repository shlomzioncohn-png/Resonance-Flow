package com.rnd.inbox.insight.mapper;

import com.rnd.inbox.insight.dto.InsightCreateRequestDto;
import com.rnd.inbox.insight.dto.InsightResponseDto;
import com.rnd.inbox.insight.model.Insight;
import com.rnd.inbox.insight.model.InsightType;
import com.rnd.inbox.user.model.User;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class InsightMapper {

    private final Clock clock;

    public InsightMapper(Clock clock) {
        this.clock = clock;
    }

    // The insight always belongs to the given user and to that user's own tenant.
    public Insight toEntity(InsightCreateRequestDto dto, User user) {
        Insight insight = new Insight();
        insight.setTenant(user.getTenant());
        insight.setUser(user);
        insight.setInsightType(InsightType.valueOf(dto.getInsightType()));
        insight.setInsightText(dto.getInsightText());
        insight.setRecommendationText(isBlank(dto.getRecommendationText()) ? null : dto.getRecommendationText());
        insight.setEvidenceText(dto.getEvidenceText());
        insight.setPeriodFrom(dto.getPeriodFrom());
        insight.setPeriodTo(dto.getPeriodTo());
        insight.setCreatedAt(Instant.now(clock));
        return insight;
    }

    public InsightResponseDto toResponseDto(Insight insight) {
        return new InsightResponseDto(
                insight.getId(),
                insight.getInsightType(),
                insight.getInsightText(),
                insight.getRecommendationText(),
                insight.getEvidenceText(),
                insight.getPeriodFrom(),
                insight.getPeriodTo(),
                insight.getCreatedAt());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
