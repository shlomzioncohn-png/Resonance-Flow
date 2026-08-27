package com.rnd.bridge.intake.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Config for RealWhatsAppSourceReader. There is no official export API for personal
 * WhatsApp (see plan.md) — someone uses WhatsApp's own "Export Chat" feature and places the
 * resulting .txt file (plus any attachment files, alongside it) into importDir; the reader
 * just picks up whatever .txt files are there.
 */
@ConfigurationProperties(prefix = "bridge.intake.whatsapp")
public record WhatsAppImportProperties(
        String importDir,
        @DefaultValue("kamatech") String tenantId) {
}
