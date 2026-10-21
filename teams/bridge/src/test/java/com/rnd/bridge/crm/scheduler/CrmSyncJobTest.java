package com.rnd.bridge.crm.scheduler;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.crm.CrmSourceResult;
import com.rnd.bridge.crm.CrmSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code syncEnabled}/{@code cronExpression} are {@code @Value}-injected in production, set here
 * via {@link ReflectionTestUtils}. {@link CrmSyncJob#runOnce()} sums {@link CrmSourceResult}s from
 * a mocked {@link CrmSyncService} and throws, naming any failed source, only after every source ran.
 */
class CrmSyncJobTest {

    private final CrmSyncService crmSyncService = mock(CrmSyncService.class);
    private final CrmSyncJob job = new CrmSyncJob(crmSyncService);

    @Test
    void nameIsCrm() {
        assertEquals("crm", job.name());
    }

    @Test
    void enabledAndCronReflectTheInjectedValues() {
        ReflectionTestUtils.setField(job, "syncEnabled", true);
        ReflectionTestUtils.setField(job, "cronExpression", "0 0 3 * * ?");

        assertTrue(job.enabled());
        assertEquals("0 0 3 * * ?", job.cron());

        ReflectionTestUtils.setField(job, "syncEnabled", false);

        assertFalse(job.enabled());
    }

    @Test
    void runOnceSumsCountsAcrossAllSuccessfulSources() {
        when(crmSyncService.syncAll()).thenReturn(List.of(
                new CrmSourceResult("hubspot", 3, 3, 0, List.of(), 0, null),
                new CrmSourceResult("salesforce", 2, 2, 0, List.of(), 0, null)));

        IntakeResult result = job.runOnce();

        assertEquals(5, result.read());
        assertEquals(5, result.published());
        assertEquals(0, result.rejected());
    }

    @Test
    void skippedAtReadAloneDoesNotFailTheJob() {
        when(crmSyncService.syncAll()).thenReturn(List.of(
                new CrmSourceResult("hubspot", 29, 29, 0, List.of(), 3, null)));

        IntakeResult result = job.runOnce();

        assertEquals(29, result.published());
    }

    @Test
    void oneSourceFailingThrowsAfterAllSourcesRanNamingIt() {
        when(crmSyncService.syncAll()).thenReturn(List.of(
                new CrmSourceResult("hubspot", 0, 0, 0, List.of(), 0, "HubSpot unreachable"),
                new CrmSourceResult("salesforce", 2, 2, 0, List.of(), 0, null)));

        RuntimeException thrown = assertThrows(RuntimeException.class, job::runOnce);

        assertTrue(thrown.getMessage().contains("hubspot"), () -> "expected 'hubspot' in: " + thrown.getMessage());
        assertFalse(thrown.getMessage().contains("salesforce"),
                () -> "salesforce succeeded and should not be named: " + thrown.getMessage());
    }

    @Test
    void allSourcesFailingNamesAllOfThemInTheThrownMessage() {
        when(crmSyncService.syncAll()).thenReturn(List.of(
                new CrmSourceResult("hubspot", 0, 0, 0, List.of(), 0, "HubSpot unreachable"),
                new CrmSourceResult("salesforce", 0, 0, 0, List.of(), 0, "Salesforce unreachable")));

        RuntimeException thrown = assertThrows(RuntimeException.class, job::runOnce);

        assertTrue(thrown.getMessage().contains("hubspot"));
        assertTrue(thrown.getMessage().contains("salesforce"));
    }
}
