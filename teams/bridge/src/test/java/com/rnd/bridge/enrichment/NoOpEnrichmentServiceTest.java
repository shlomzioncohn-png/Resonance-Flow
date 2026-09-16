package com.rnd.bridge.enrichment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoOpEnrichmentServiceTest {

    private final EnrichmentService service = new NoOpEnrichmentService();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "this PR might block the release of #789 but I'm not sure"})
    void neverThrowsRegardlessOfInputQuality(String freeText) {
        assertDoesNotThrow(() -> service.enrich("crm:D-1", freeText));
    }

    @Test
    void returnsAnEmptyResultKeyedToTheGivenSourceRef() {
        TaskEnrichment result = service.enrich("github:owner/repo#42", "Fixes #45, blocks #123, due Friday");

        assertEquals("github:owner/repo#42", result.sourceRef());
        assertNull(result.dueDate());
        assertNull(result.severity());
        assertTrue(result.dependencies().isEmpty());
    }
}
