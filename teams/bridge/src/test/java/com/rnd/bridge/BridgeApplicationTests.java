package com.rnd.bridge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

// bridge.crm.organization-id and bridge.postgres.* have no defaults in application.yml on
// purpose (a missing value must fail a real startup) - these test-only values stand in so a
// full context load doesn't need CRM_ORGANIZATION_ID/BRIDGE_DB_* set. spring.sql.init.mode is
// overridden to "never" here: the app's own startup always creates control_plane_state (see
// application.yml), but this test's job is proving the context wires up, not exercising a live
// Postgres connection - same no-real-broker tolerance already given to Kafka (see
// teams/bridge/CLAUDE.md §7).
@SpringBootTest
@TestPropertySource(properties = {
        "bridge.crm.organization-id=org-test-0000",
        "bridge.postgres.url=jdbc:postgresql://localhost:5432/bridge_test",
        "bridge.postgres.user=test",
        "bridge.postgres.password=test",
        "spring.sql.init.mode=never"
})
class BridgeApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring application context starts successfully.
    }
}
