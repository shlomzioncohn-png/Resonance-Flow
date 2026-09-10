package com.rnd.bridge.intake.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bridge.intake.chat")
public record ChatSessionProperties(
        int sessionGapMinutes) {
}
