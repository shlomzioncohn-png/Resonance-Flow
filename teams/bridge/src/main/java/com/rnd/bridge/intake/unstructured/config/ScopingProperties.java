package com.rnd.bridge.intake.unstructured.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bridge.intake.scoping")
public record ScopingProperties(
        List<String> allowedDomains,
        List<String> allowedKeywords) {
}
