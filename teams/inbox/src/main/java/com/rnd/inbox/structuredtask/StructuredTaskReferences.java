package com.rnd.inbox.structuredtask;

import com.rnd.inbox.project.Project;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.User;

public record StructuredTaskReferences(
        Tenant tenant,
        Project project,
        User createdBy) {
}
