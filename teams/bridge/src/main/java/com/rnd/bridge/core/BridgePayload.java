package com.rnd.bridge.core;

/**
 * Anything Bridge publishes. Deliberately minimal: there is no
 * {@code sourceType()} here because {@code Task} and a future
 * {@code RawObjectEvent} use different source-type enums, so it cannot be
 * typed at this level without coupling core to one of them.
 */
public interface BridgePayload {

    String tenantId();

    String dedupKey();
}
