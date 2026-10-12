package com.rnd.bridge.crm;

import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.model.Task;

import java.util.regex.Pattern;

/**
 * CRM-only wrapper around the shared {@link TaskValidator}: runs every check that validator
 * already does, then adds tenant/{@code sourceRef} rules specific to a lane that actually has a
 * tenant id (finding F5 in the CRM-lane audit).
 * <p>
 * {@code GitHubClient}, {@code GitHubKafkaProducer}, {@code JiraClient}, {@code JiraKafkaProducer}
 * and {@code TaskEnrichmentConsumer} all share {@link TaskValidator} directly and never set
 * {@code Task.organizationId} - adding a tenant check to {@link TaskValidator} itself would reject
 * every task those lanes publish. {@link CrmLane} uses this class instead, never the shared
 * validator on its own, so the tenant/{@code sourceRef} rules apply only where a tenant id is
 * actually expected.
 */
public final class CrmTaskValidator implements PayloadValidator<Task> {

    /**
     * The only tenant id value {@code CRM_ORGANIZATION_ID} may default to without the
     * {@code kafka} profile (see {@code application.yml}'s {@code bridge.crm.organization-id} and
     * {@code CrmLaneConfig.dealToTaskMapper}) - a clearly fake, canonically-shaped UUID (RFC 4122's
     * "nil" UUID), not the old {@code "local-crm-org"} placeholder. This class validates tenant-id
     * shape unconditionally, including for a no-profile local/demo run through
     * {@code JsonDealRepository} + {@code LoggingPayloadPublisher}, so the default itself has to be
     * UUID-shaped or every task in that run would be rejected - that regression is exactly why this
     * constant exists, rather than leaving the non-UUID placeholder in place.
     * {@code CrmLaneConfig}'s {@code kafka}-profile startup check still rejects this exact value by
     * name (not just by shape), so forgetting to override {@code CRM_ORGANIZATION_ID} for a real
     * deployment is still caught.
     */
    public static final String LOCAL_PLACEHOLDER_TENANT_ID = "00000000-0000-0000-0000-000000000000";

    // Canonical 36-char 8-4-4-4-12 hex-hyphenated shape, case-insensitive - not
    // UUID.fromString(), which is lenient enough to accept malformed strings like "1-1-1-1-1".
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final TaskValidator delegate;

    public CrmTaskValidator(TaskValidator delegate) {
        this.delegate = delegate;
    }

    @Override
    public void validate(Task task) {
        delegate.validate(task);

        String tenantId = task.organizationId();
        if (tenantId == null || tenantId.isBlank()) {
            throw invalid("organizationId", tenantId, "must not be blank");
        }
        if (!isValidTenantId(tenantId)) {
            throw invalid("organizationId", tenantId, "must be a valid UUID");
        }
        if (task.sourceRef() == null || task.sourceRef().isBlank()) {
            throw invalid("sourceRef", task.sourceRef(), "must not be blank");
        }
    }

    /** Shared with {@code CrmLaneConfig}'s startup check, so the two agree on what "valid" means. */
    public static boolean isValidTenantId(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }

    private static ValidationException invalid(String field, Object value, String reason) {
        return new ValidationException("Invalid Task." + field + " = " + value + ": " + reason);
    }
}
