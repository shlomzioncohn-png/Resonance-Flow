package com.rnd.inbox.task;

import com.rnd.inbox.shared.messaging.validation.ValidEnum;
import com.rnd.inbox.shared.messaging.validation.ValidUuid;
import jakarta.validation.constraints.NotBlank;

public class AssigneeDto {

    @NotBlank
    @ValidEnum(enumClass = AssigneeType.class)
    private String assigneeType;

    @NotBlank
    @ValidUuid
    private String id;

    @NotBlank
    @ValidEnum(enumClass = AssignmentRole.class)
    private String assignmentRole;

    @ValidUuid
    private String assignedBy;

    public String getAssigneeType() {
        return assigneeType;
    }

    public void setAssigneeType(String assigneeType) {
        this.assigneeType = assigneeType;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAssignmentRole() {
        return assignmentRole;
    }

    public void setAssignmentRole(String assignmentRole) {
        this.assignmentRole = assignmentRole;
    }

    public String getAssignedBy() {
        return assignedBy;
    }

    public void setAssignedBy(String assignedBy) {
        this.assignedBy = assignedBy;
    }
}
