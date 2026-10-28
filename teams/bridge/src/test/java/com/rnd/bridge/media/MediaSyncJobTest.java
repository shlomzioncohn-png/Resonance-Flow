package com.rnd.bridge.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.sync.SyncJob;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MediaSyncJobTest {

    private final MediaIntakeOrchestrator orchestrator = mock(MediaIntakeOrchestrator.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(MediaIntakeOrchestrator.class, () -> orchestrator)
            .withUserConfiguration(MediaSyncJob.class);

    @Test
    void runsTheSameIntakeAsTheEndpointAndReportsItsOutcome() {
        when(orchestrator.runIntake()).thenReturn(new MediaIntakeOrchestrator.IntakeSummary(
                List.of("ZOOM/t/2026-10-26/a.mp4", "ZOOM/t/2026-10-26/a.vtt"),
                List.of("dup.mp4"), List.of("tiny.mp3"), List.of("broken.pdf")));

        IntakeResult result = new MediaSyncJob(orchestrator, true, "0 */5 * * * ?").runOnce();

        verify(orchestrator).runIntake();
        assertThat(result.read()).isEqualTo(5);
        assertThat(result.published()).isEqualTo(2);
        assertThat(result.rejected()).isEqualTo(2);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void emptyRunReportsNothing() {
        when(orchestrator.runIntake()).thenReturn(
                new MediaIntakeOrchestrator.IntakeSummary(List.of(), List.of(), List.of(), List.of()));

        IntakeResult result = new MediaSyncJob(orchestrator, true, "0 */5 * * * ?").runOnce();

        assertThat(result.read()).isZero();
        assertThat(result.published()).isZero();
    }

    @Test
    void offByDefaultEveryFiveMinutesWhenOn() {
        contextRunner.run(context -> {
            SyncJob job = context.getBean(SyncJob.class);
            assertThat(job.name()).isEqualTo("media");
            assertThat(job.enabled()).isFalse();
            assertThat(job.cron()).isEqualTo("0 */5 * * * ?");
        });
    }

    @Test
    void enabledAndCronComeFromConfig() {
        contextRunner.withPropertyValues(
                        "bridge.media.sync.cron.enabled=true",
                        "bridge.media.sync.cron.expression=0 */2 * * * ?")
                .run(context -> {
                    SyncJob job = context.getBean(SyncJob.class);
                    assertThat(job.enabled()).isTrue();
                    assertThat(job.cron()).isEqualTo("0 */2 * * * ?");
                });
    }
}
