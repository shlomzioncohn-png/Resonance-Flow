package com.rnd.chain.exception;

/**
 * Thrown when a dependency edge would connect two tasks that don't share a confirmed tenant -
 * either their {@code tenantId}s differ, or either one is still null (issue #580: a null tenant
 * is treated as "not yet known", not "any tenant", so it's rejected the same as a mismatch).
 * Mapped to HTTP 400 by {@link GlobalExceptionHandler}.
 */
public class CrossTenantDependencyException extends InvalidDependencyException {

    public CrossTenantDependencyException(String message) {
        super(message);
    }
}
