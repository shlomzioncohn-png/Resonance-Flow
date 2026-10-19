package com.rnd.bridge.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MediaIntakeSchedulerTest {

    private final MediaIntakeOrchestrator orchestrator = mock(MediaIntakeOrchestrator.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(MediaIntakeOrchestrator.class, () -> orchestrator)
            .withUserConfiguration(MediaIntakeScheduler.class);

    @Test
    void pollRunsTheSameIntakeAsTheEndpoint() {
        when(orchestrator.runIntake()).thenReturn(new MediaIntakeOrchestrator.IntakeSummary(
                List.of("SCANNED_DOC/demo-tenant/2026-10-13/x.pdf"), List.of(), List.of(), List.of()));

        new MediaIntakeScheduler(orchestrator).pollIntakeFolders();

        verify(orchestrator).runIntake();
    }

    @Test
    void failedRunIsLoggedNotThrown() {
        when(orchestrator.runIntake()).thenThrow(new IllegalStateException("MinIO unreachable"));

        assertThatCode(() -> new MediaIntakeScheduler(orchestrator).pollIntakeFolders())
                .doesNotThrowAnyException();
    }

    @Test
    void notRegisteredUnlessEnabled() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(MediaIntakeScheduler.class));
        contextRunner.withPropertyValues("bridge.media.schedule.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(MediaIntakeScheduler.class));
    }

    @Test
    void registeredWhenEnabled() {
        contextRunner.withPropertyValues("bridge.media.schedule.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(MediaIntakeScheduler.class));
    }
}
