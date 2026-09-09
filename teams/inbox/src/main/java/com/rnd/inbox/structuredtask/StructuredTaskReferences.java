package com.rnd.inbox.structuredtask;

import com.rnd.inbox.project.Project;
import com.rnd.inbox.team.Team;
import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.user.User;

public record StructuredTaskReferences(
        Tenant tenant,
        Project project,
        User assignee,
        Team assigneeTeam,
        User assignedBy) {
}
