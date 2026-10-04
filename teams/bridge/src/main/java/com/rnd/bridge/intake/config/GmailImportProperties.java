package com.rnd.bridge.intake.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Config for RealEmailSourceReader. No mailbox address is needed - "me" always refers to
 * whichever account completed the OAuth consent (see GoogleCredentialsProvider).
 */
@ConfigurationProperties(prefix = "bridge.intake.email")
public record GmailImportProperties(@DefaultValue("kamatech") String tenantId) {
}
