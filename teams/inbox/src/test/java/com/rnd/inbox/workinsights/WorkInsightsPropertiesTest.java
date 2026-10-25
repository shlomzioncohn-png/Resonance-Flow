package com.rnd.inbox.workinsights;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class WorkInsightsPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(WorkInsightsProperties.class);

    @Test
    void load_withDefaults_startsWithDefaultValues() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            WorkInsightsProperties properties = context.getBean(WorkInsightsProperties.class);
            assertThat(properties.getShortMaxMinutes()).isEqualTo(30);
            assertThat(properties.getMediumMaxMinutes()).isEqualTo(120);
        });
    }

    @Test
    void load_whenShortMaxNotBelowMediumMax_failsToStart() {
        contextRunner
                .withPropertyValues(
                        "inbox.work-insights.short-max-minutes=200",
                        "inbox.work-insights.medium-max-minutes=120")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("short-max-minutes must be less than medium-max-minutes"));
    }

    @Test
    void load_whenShortMaxEqualsMediumMax_failsToStart() {
        contextRunner
                .withPropertyValues(
                        "inbox.work-insights.short-max-minutes=60",
                        "inbox.work-insights.medium-max-minutes=60")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void load_whenWindowDaysIsZero_failsToStart() {
        contextRunner
                .withPropertyValues("inbox.work-insights.window-days=0")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("windowDays"));
    }

    @Test
    void load_whenDefaultEffortMinutesIsNegative_failsToStart() {
        contextRunner
                .withPropertyValues("inbox.work-insights.default-effort-minutes=-5")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("defaultEffortMinutes"));
    }
}
