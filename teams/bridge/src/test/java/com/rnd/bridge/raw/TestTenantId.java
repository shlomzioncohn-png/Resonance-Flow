package com.rnd.bridge.raw;

/**
 * Shared valid-UUID tenant id for tests that need a real {@code RawObjectValidator}
 * (or anything else enforcing bridge.tenant-id's UUID shape) to pass - mirrors Inbox's System
 * tenant default (see UnstructuredIntakeController's bridge.tenant-id). A deliberately
 * non-UUID value like "kamatech" still belongs inline where a test is proving rejection of one
 * (see RawObjectValidatorTest.validateRejectsANonUuidTenantId).
 */
public final class TestTenantId {

    public static final String VALID = "00000000-0000-0000-0000-000000000001";

    private TestTenantId() {}
}
