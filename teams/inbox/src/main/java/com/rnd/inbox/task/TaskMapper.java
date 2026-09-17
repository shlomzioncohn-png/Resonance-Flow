package com.rnd.inbox.task;

import org.springframework.stereotype.Component;

@Component
public class TaskMapper {

    public void applyUpdate(Task existing, Task changes) {
        existing.setTitle(changes.getTitle());
        existing.setDescription(changes.getDescription());
        existing.setStatus(changes.getStatus());
        existing.setTier(changes.getTier());
        existing.setRoleContext(changes.getRoleContext());
        existing.setUrgency(changes.getUrgency());
        existing.setEffortEstimateMinutes(changes.getEffortEstimateMinutes());
        existing.setDeadline(changes.getDeadline());
        existing.setProject(changes.getProject());
        existing.setEffortEstimateConfidence(changes.getEffortEstimateConfidence());
        existing.setSuggestedOwnerUser(changes.getSuggestedOwnerUser());
        existing.setSuggestedOwnerConfidence(changes.getSuggestedOwnerConfidence());
        existing.setOwner(changes.getOwner());
    }
}
