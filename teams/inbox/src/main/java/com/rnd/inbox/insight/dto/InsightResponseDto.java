package com.rnd.inbox.insight.dto;

import com.rnd.inbox.insight.model.InsightType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class InsightResponseDto {

    private final UUID id;
    private final InsightType insightType;
    private final String insightText;
    private final String recommendationText;
    private final String evidenceText;
    private final LocalDate periodFrom;
    private final LocalDate periodTo;
    private final Instant createdAt;

    public InsightResponseDto(UUID id, InsightType insightType, String insightText, String recommendationText,
                              String evidenceText, LocalDate periodFrom, LocalDate periodTo, Instant createdAt) {
        this.id = id;
        this.insightType = insightType;
        this.insightText = insightText;
        this.recommendationText = recommendationText;
        this.evidenceText = evidenceText;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public InsightType getInsightType() {
        return insightType;
    }

    public String getInsightText() {
        return insightText;
    }

    public String getRecommendationText() {
        return recommendationText;
    }

    public String getEvidenceText() {
        return evidenceText;
    }

    public LocalDate getPeriodFrom() {
        return periodFrom;
    }

    public LocalDate getPeriodTo() {
        return periodTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
