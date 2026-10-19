package com.rnd.chain.exception;

/**
 * Thrown when a {@code task.updated} event claims a {@code tenantId} different from what Chain
 * already has stored for that task (issue #580). Confirmed with the Inbox team: {@code tenant_id}
 * is fixed from task creation and never legitimately changes afterward, so this always indicates
 * a bug upstream - never applied, never retried. Deliberately not an {@link InvalidDependencyException}:
 * this isn't about a dependency edge, it's a task's own identity data contradicting itself. Never
 * reachable from REST (task mutation is event-only), so it has no {@code GlobalExceptionHandler}
 * mapping.
 */
public class TenantReassignmentException extends BadRequestException {

    public TenantReassignmentException(String message) {
        super(message);
    }
}
