package com.rnd.bridge.intake.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for the unstructured-intake lanes' tenant id. Binds
 * {@code bridge.tenant-id} exactly once; every lane reader (Calendar, Drive, Email, Slack,
 * WhatsApp) and {@code UnstructuredIntakeController} get the tenant by injecting this bean
 * instead of each independently re-binding the same property (or, worse, defaulting it
 * per-class - see {@code DriveImportProperties}' now-removed {@code @DefaultValue("kamatech")}).
 */
@Component
public class BridgeTenantId {

    private final String value;

    public BridgeTenantId(@Value("${bridge.tenant-id}") String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
