package com.rnd.inbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// EnableScheduling is required for OutboxWorker.pollAndPublish and
// OutboxCleanupJob.cleanup to actually run - without it, @Scheduled
// methods are silently never invoked, with no error or warning.
@EnableScheduling
@SpringBootApplication
public class InboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(InboxApplication.class, args);
    }
}