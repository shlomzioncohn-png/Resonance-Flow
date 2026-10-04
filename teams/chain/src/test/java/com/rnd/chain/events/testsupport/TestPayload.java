package com.rnd.chain.events.testsupport;

/**
 * Minimal test-only payload type used across Chain's event tests to verify
 * deserialization and routing. Extracted (PLAN.md Finding 6) from two
 * previously identical nested classes: ChainEventConsumerTest.TestPayload
 * and ChainEventConsumerIntegrationTest.ItPayload.
 */
public class TestPayload {
    private String value;

    public TestPayload() {
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
