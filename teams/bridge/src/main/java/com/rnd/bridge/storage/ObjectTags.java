package com.rnd.bridge.storage;

import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.time.Instant;
import java.util.Map;

/** The tag set every landed raw object carries in MinIO: source_type, tenant_id, ingested_at, scope_decision. */
public final class ObjectTags {

    private ObjectTags() {
    }

    public static Map<String, String> of(
            RawSourceType sourceType, String tenantId, Instant ingestedAt, ScopeDecision scopeDecision) {
        return Map.of(
                "source_type", sourceType.name(),
                "tenant_id", tenantId,
                "ingested_at", ingestedAt.toString(),
                "scope_decision", scopeDecision.name());
    }
}
