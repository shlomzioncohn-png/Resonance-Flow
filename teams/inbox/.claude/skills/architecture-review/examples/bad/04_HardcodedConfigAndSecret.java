// Violation example for the architecture-review skill's test scenarios (see ../../TEST-SCENARIOS.md).
// Expected findings:
//  - Critical — secret hard-coded in code (CLAUDE.md §12 / bootcamp guide Secrets rule)
//  - Major — hard-coded, environment-specific configuration instead of an env var (CLAUDE.md §12)
package com.rnd.inbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationConfig {

    // literal credential checked into source control
    private static final String SLACK_WEBHOOK_TOKEN = "xoxb-9327483279-847592734-AbCdEfGhIjKlMnOpQrStUvWx";

    @Bean
    public String notificationServiceUrl() {
        // environment-specific value hard-coded instead of coming from application.yml/.env
        return "https://notifications-prod.internal.example.com:8443";
    }
}
