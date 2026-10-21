package com.rnd.inbox.insight.dto;

import com.rnd.inbox.insight.model.InsightType;
import com.rnd.inbox.shared.messaging.validation.ValidEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

// No userId / tenantId here on purpose: an insight is always created for the calling user (X-Actor-Id).
public class InsightCreateRequestDto {

    @NotBlank
    @ValidEnum(enumClass = InsightType.class)
    private String insightType;

    @NotBlank
    @Size(max = 1000)
    private String insightText;

    // Optional - not every insight warrants a recommendation. Blank text is stored as NULL (InsightMapper.toEntity).
    @Size(max = 1000)
    private String recommendationText;

    @NotBlank
    @Size(max = 1000)
    private String evidenceText;

    @NotNull
    private LocalDate periodFrom;

    @NotNull
    private LocalDate periodTo;

    public String getInsightType() {
        return insightType;
    }

    public void setInsightType(String insightType) {
        this.insightType = insightType;
    }

    public String getInsightText() {
        return insightText;
    }

    public void setInsightText(String insightText) {
        this.insightText = insightText;
    }

    public String getRecommendationText() {
        return recommendationText;
    }

    public void setRecommendationText(String recommendationText) {
        this.recommendationText = recommendationText;
    }

    public String getEvidenceText() {
        return evidenceText;
    }

    public void setEvidenceText(String evidenceText) {
        this.evidenceText = evidenceText;
    }

    public LocalDate getPeriodFrom() {
        return periodFrom;
    }

    public void setPeriodFrom(LocalDate periodFrom) {
        this.periodFrom = periodFrom;
    }

    public LocalDate getPeriodTo() {
        return periodTo;
    }

    public void setPeriodTo(LocalDate periodTo) {
        this.periodTo = periodTo;
    }
}
