package com.rnd.bridge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

// bridge.crm.organization-id has no default in application.yml on purpose
// (a missing value must fail a real startup) - this test-only value stands
// in for it so a full context load doesn't need CRM_ORGANIZATION_ID set.
@SpringBootTest
@TestPropertySource(properties = "bridge.crm.organization-id=org-test-0000")
class BridgeApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring application context starts successfully.
    }
}
