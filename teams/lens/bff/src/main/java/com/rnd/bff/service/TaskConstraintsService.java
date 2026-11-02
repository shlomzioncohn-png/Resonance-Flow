package com.rnd.bff.service;

import com.rnd.bff.client.ChainClient;
import com.rnd.bff.dto.ConstraintDTO;
import com.rnd.bff.exception.ConstraintNotFoundException;
import com.rnd.bff.exception.ServiceUnavailableException;
import com.rnd.bff.exception.TaskNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The constraints of one task in the Bottlenecks screen's task drawer, and the
 * manager's status change (PENDING / SATISFIED / WAIVED).
 *
 * - The tenant comes from the user's Inbox record (UserTenantResolver), never
 *   from the frontend.
 * - Chain answers 404 both for a task outside the tenant and for a missing
 *   constraint - passed on as 404.
 * - Any other Chain failure (other 4xx, 5xx, Chain unreachable) is 503.
 */
@Service
public class TaskConstraintsService {

    private static final Logger logger = LoggerFactory.getLogger(TaskConstraintsService.class);

    private final ChainClient chainClient;
    private final UserTenantResolver tenantResolver;

    public TaskConstraintsService(ChainClient chainClient, UserTenantResolver tenantResolver) {
        this.chainClient = chainClient;
        this.tenantResolver = tenantResolver;
    }

    /** @param userId the acting manager (X-User-Id) */
    public List<ConstraintDTO> getConstraints(String userId, String taskId) {
        String tenantId = tenantResolver.tenantOf(userId);

        Optional<List<ConstraintDTO>> constraints;
        try {
            constraints = chainClient.getConstraints(taskId, tenantId);
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("chain", "The task's constraints are unavailable", ex);
        }
        List<ConstraintDTO> result = constraints.orElseThrow(() -> new TaskNotFoundException(taskId));
        logger.info("Task constraints - taskId: {}, count: {}", taskId, result.size());
        return result;
    }

    /**
     * @param userId the acting manager (X-User-Id)
     * @param status PENDING, SATISFIED or WAIVED (validated by ConstraintStatusUpdateDTO)
     */
    public ConstraintDTO updateStatus(String userId, String taskId, String constraintId, String status) {
        String tenantId = tenantResolver.tenantOf(userId);

        Optional<ConstraintDTO> updated;
        try {
            updated = chainClient.updateConstraintStatus(taskId, constraintId, status, tenantId);
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("chain", "Couldn't update the constraint's status", ex);
        }
        ConstraintDTO result = updated.orElseThrow(() -> new ConstraintNotFoundException(taskId, constraintId));
        logger.info("Constraint status updated - actorId: {}, taskId: {}, constraintId: {}, status: {}",
                userId, taskId, constraintId, result.getStatus());
        return result;
    }
}
