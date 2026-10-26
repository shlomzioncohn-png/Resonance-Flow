package com.rnd.bridge.intake.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Config for RealCalendarSourceReader.
 */
@ConfigurationProperties(prefix = "bridge.intake.calendar")
public record CalendarImportProperties(
        @DefaultValue("primary") String calendarId) {
}
