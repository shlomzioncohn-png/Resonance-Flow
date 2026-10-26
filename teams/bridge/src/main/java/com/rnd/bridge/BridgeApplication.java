package com.rnd.bridge;

import com.rnd.bridge.intake.config.CalendarImportProperties;
import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.config.DriveImportProperties;
import com.rnd.bridge.intake.config.GmailImportProperties;
import com.rnd.bridge.intake.config.GoogleOAuthProperties;
import com.rnd.bridge.intake.config.ScopingProperties;
import com.rnd.bridge.intake.config.SlackApiProperties;
import com.rnd.bridge.intake.config.WhatsAppImportProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.rnd.bridge.ai.AiProperties;
import com.rnd.bridge.media.classification.MediaClassificationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@SpringBootApplication
@EnableConfigurationProperties({
    ScopingProperties.class,
    ChatSessionProperties.class,
    WhatsAppImportProperties.class,
    SlackApiProperties.class,
    GoogleOAuthProperties.class,
    CalendarImportProperties.class,
    DriveImportProperties.class,
    GmailImportProperties.class,
    MediaClassificationProperties.class,
    AiProperties.class
})
public class BridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(BridgeApplication.class, args);
    }

    // Injected rather than Clock.systemUTC() called directly, so replay-window checks (see
    // SlackSignatureVerifier) and session-deadline checks (see SlackSessionScheduler) are
    // deterministically testable.
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    // Dynamic one-shot deadlines (SlackSessionScheduler), not a periodic scanner - each group
    // gets its own schedule(Runnable, Instant) call, never a fixed-rate/fixed-delay poll.
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("slack-session-scheduler-");
        return scheduler;
    }
}
