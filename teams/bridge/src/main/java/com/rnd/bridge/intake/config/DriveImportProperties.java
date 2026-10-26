package com.rnd.bridge.intake.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for RealDriveSourceReader.
 */
@ConfigurationProperties(prefix = "bridge.intake.drive")
public record DriveImportProperties(String rootFolderId) {
}
