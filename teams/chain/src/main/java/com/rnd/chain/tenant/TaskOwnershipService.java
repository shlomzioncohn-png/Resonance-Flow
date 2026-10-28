package com.rnd.chain.tenant;

import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskOwnershipService {

    private final TaskRepository taskRepository;

    /**
     * A task of another tenant (or with a still-null tenant) fails exactly like a missing one, so
     * its existence is not revealed.
     */
    public void requireTaskInTenant(UUID taskId, UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        taskRepository.findById(taskId)
                .filter(task -> tenantId.equals(task.getTenantId()))
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }
}
