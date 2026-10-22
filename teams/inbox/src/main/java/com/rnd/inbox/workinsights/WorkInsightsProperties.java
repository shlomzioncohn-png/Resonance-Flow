package com.rnd.inbox.workinsights;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "inbox.work-insights")
public class WorkInsightsProperties {

    @Positive
    private int windowDays = 30;

    @Positive
    private int shortMaxMinutes = 30;

    @Positive
    private int mediumMaxMinutes = 120;

    @Positive
    private int defaultEffortMinutes = 30;

    @Positive
    private int dueSoonDays = 7;

    @AssertTrue(message = "short-max-minutes must be less than medium-max-minutes")
    public boolean isShortMaxBelowMediumMax() {
        return shortMaxMinutes < mediumMaxMinutes;
    }

    public int getWindowDays() {
        return windowDays;
    }

    public void setWindowDays(int windowDays) {
        this.windowDays = windowDays;
    }

    public int getShortMaxMinutes() {
        return shortMaxMinutes;
    }

    public void setShortMaxMinutes(int shortMaxMinutes) {
        this.shortMaxMinutes = shortMaxMinutes;
    }

    public int getMediumMaxMinutes() {
        return mediumMaxMinutes;
    }

    public void setMediumMaxMinutes(int mediumMaxMinutes) {
        this.mediumMaxMinutes = mediumMaxMinutes;
    }

    public int getDefaultEffortMinutes() {
        return defaultEffortMinutes;
    }

    public void setDefaultEffortMinutes(int defaultEffortMinutes) {
        this.defaultEffortMinutes = defaultEffortMinutes;
    }

    public int getDueSoonDays() {
        return dueSoonDays;
    }

    public void setDueSoonDays(int dueSoonDays) {
        this.dueSoonDays = dueSoonDays;
    }
}
