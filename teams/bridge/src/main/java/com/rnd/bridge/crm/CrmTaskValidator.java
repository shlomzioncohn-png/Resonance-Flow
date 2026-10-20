package com.rnd.bridge.crm;

import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.model.Task;

import java.util.regex.Pattern;

/**
 * CRM-only wrapper around the shared {@link TaskValidator}: adds tenant-id/{@code sourceRef}
 * checks (finding F5). Other lanes (GitHub/Jira/enrichment) share {@link TaskValidator} directly
 * and never set a tenant id, so they don't use this class.
 */
public final class CrmTaskValidator implements PayloadValidator<Task> {

    /**
     * The only tenant id {@code CRM_ORGANIZATION_ID} may default to without the {@code kafka}
     * profile - a fake but UUID-shaped placeholder. {@code CrmLaneConfig}'s {@code kafka}-profile
     * check still rejects this exact value by name, so leaving it unset for a real deployment is still caught.
     */
    public static final String LOCAL_PLACEHOLDER_TENANT_ID = "00000000-0000-0000-0000-000000000000";

    // Canonical 8-4-4-4-12 hex shape - stricter than UUID.fromString(), which accepts malformed input.
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
