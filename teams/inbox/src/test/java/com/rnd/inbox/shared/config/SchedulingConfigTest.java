package com.rnd.inbox.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void scheduling_whenPropertyIsMissing_isEnabled() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void scheduling_whenPropertyIsTrue_isEnabled() {
        contextRunner.withPropertyValues("inbox.scheduling.enabled=true").run(context ->
                assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void scheduling_whenPropertyIsFalse_isDisabled() {
        contextRunner.withPropertyValues("inbox.scheduling.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }
}
