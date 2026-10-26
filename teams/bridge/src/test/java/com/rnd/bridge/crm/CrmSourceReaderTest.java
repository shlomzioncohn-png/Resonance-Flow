package com.rnd.bridge.crm;

import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.crm.repository.DealRepository;
import com.rnd.bridge.crm.repository.DealSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CrmSourceReader} keeps its original single-repository behavior unchanged and fails loudly
 * in {@link CrmSourceReader#read(ReadCursor)} - never the constructor, so the application still
 * starts with both CRM profiles active - if asked to read with more than one source. The real
 * multi-source sync path is {@code CrmSyncService} (Step 2), not this reader.
 */
class CrmSourceReaderTest {

    private static Deal deal(String dealId) {
        return Deal.builder().dealId(dealId).stage(DealStage.PROSPECT).stageChangedAt(Instant.now()).build();
    }

    @Test
    void readsOneRepositoryExactlyAsBefore() {
        Deal dealA = deal("D-1");
        DealRepository repository = mock(DealRepository.class);
        when(repository.snapshot()).thenReturn(
                new DealSnapshot(Instant.now(), List.of(dealA), Map.of("D-1", List.of(dealA)), List.of("D-1"), 0));

        CrmSourceReader reader = new CrmSourceReader(List.of(repository));

        List<Deal> read = reader.read(ReadCursor.full()).toList();

        assertEquals(List.of(dealA), read);
    }

    @Test
    void constructingWithMultipleRepositoriesDoesNotThrow() {
        DealRepository hubspot = mock(DealRepository.class);
        DealRepository salesforce = mock(DealRepository.class);

        assertDoesNotThrow(() -> new CrmSourceReader(List.of(hubspot, salesforce)));
    }

    @Test
    void readingWithMultipleRepositoriesActiveThrowsNamingCrmSyncService() {
        DealRepository hubspot = mock(DealRepository.class);
        DealRepository salesforce = mock(DealRepository.class);
        CrmSourceReader reader = new CrmSourceReader(List.of(hubspot, salesforce));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> reader.read(ReadCursor.full()));

        assertEquals("CrmSourceReader supports a single CRM source; use CrmSyncService", exception.getMessage());
    }
}
