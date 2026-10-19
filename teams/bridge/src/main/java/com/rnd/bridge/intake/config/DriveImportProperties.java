package com.rnd.bridge.intake.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Config for RealDriveSourceReader.
 */
@ConfigurationProperties(prefix = "bridge.intake.drive")
public record DriveImportProperties(@DefaultValue("kamatech") String tenantId, String rootFolderId) {
}
