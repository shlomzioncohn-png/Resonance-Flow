package com.rnd.bridge.crm.repository.salesforce;

import com.rnd.bridge.crm.model.DealStage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesforceStageMappingTest {

    private static final SalesforceStageMapping DEFAULT_BANDS = new SalesforceStageMapping(20, 50, 80, 99);

    private static Map<String, Object> stageRow(String label, boolean isClosed, boolean isWon, Integer probability) {
        Map<String, Object> row = new HashMap<>();
        row.put("MasterLabel", label);
        row.put("IsClosed", isClosed);
        row.put("IsWon", isWon);
        row.put("DefaultProbability", probability);
        return row;
    }

    @Test
    void closedAndWonMapsToClosedWon() {
        Map<String, DealStage> result =
                DEFAULT_BANDS.resolveLabelsToStages(List.of(stageRow("Closed Won", true, true, 100)));

        assertEquals(DealStage.CLOSED_WON, result.get("Closed Won"));
    }

    @Test
    void closedAndNotWonMapsToClosedLost() {
        Map<String, DealStage> result =
                DEFAULT_BANDS.resolveLabelsToStages(List.of(stageRow("Closed Lost", true, false, 0)));

        assertEquals(DealStage.CLOSED_LOST, result.get("Closed Lost"));
    }

    @Test
    void openStageBandBoundariesResolveToTheExpectedStage() {
        List<Map<String, Object>> rows = List.of(
                stageRow("At 20", false, false, 20),
                stageRow("At 21", false, false, 21),
                stageRow("At 50", false, false, 50),
                stageRow("At 51", false, false, 51),
                stageRow("At 80", false, false, 80),
                stageRow("At 81", false, false, 81),
                stageRow("At 99", false, false, 99));

        Map<String, DealStage> result = DEFAULT_BANDS.resolveLabelsToStages(rows);

        assertEquals(DealStage.PROSPECT, result.get("At 20"));
        assertEquals(DealStage.QUALIFIED, result.get("At 21"));
        assertEquals(DealStage.QUALIFIED, result.get("At 50"));
        assertEquals(DealStage.PROPOSAL, result.get("At 51"));
        assertEquals(DealStage.PROPOSAL, result.get("At 80"));
        assertEquals(DealStage.NEGOTIATION, result.get("At 81"));
        assertEquals(DealStage.NEGOTIATION, result.get("At 99"));
    }

    @Test
    void openStageWithNullProbabilityFallsBackToProspect() {
        Map<String, DealStage> result =
                DEFAULT_BANDS.resolveLabelsToStages(List.of(stageRow("No default set", false, false, null)));

        assertEquals(DealStage.PROSPECT, result.get("No default set"));
    }

    @Test
    void renamedLabelAtAnUnchangedProbabilityMapsTheSameWay() {
        Map<String, DealStage> before =
                DEFAULT_BANDS.resolveLabelsToStages(List.of(stageRow("Needs Analysis", false, false, 30)));
        Map<String, DealStage> after =
                DEFAULT_BANDS.resolveLabelsToStages(List.of(stageRow("Discovery", false, false, 30)));

        assertEquals(DealStage.QUALIFIED, before.get("Needs Analysis"));
        assertEquals(DealStage.QUALIFIED, after.get("Discovery"));
    }

    @Test
    void aStageNameAbsentFromTheResolvedMapIsALookupMissNotAnException() {
        Map<String, DealStage> result =
                DEFAULT_BANDS.resolveLabelsToStages(List.of(stageRow("Qualified To Buy", false, false, 30)));

        assertNull(result.get("Some Stage Nobody Fetched"));
        assertFalse(result.containsKey("Some Stage Nobody Fetched"));
    }

    @Test
    void negativeProspectMaxFailsAtConstruction() {
        assertThrows(IllegalStateException.class, () -> new SalesforceStageMapping(-1, 50, 80, 99));
    }

    @Test
    void qualifiedMaxNotGreaterThanProspectMaxFailsAtConstruction() {
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> new SalesforceStageMapping(20, 20, 80, 99));
        assertTrue(exception.getMessage().contains("qualified-max"));
    }

    @Test
    void proposalMaxNotGreaterThanQualifiedMaxFailsAtConstruction() {
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> new SalesforceStageMapping(20, 50, 50, 99));
        assertTrue(exception.getMessage().contains("proposal-max"));
    }

    @Test
    void negotiationMaxNotGreaterThanProposalMaxFailsAtConstruction() {
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> new SalesforceStageMapping(20, 50, 80, 80));
        assertTrue(exception.getMessage().contains("negotiation-max"));
    }

    @Test
    void negotiationMaxNotExactlyNinetyNineLeavesAGapAndFailsAtConstruction() {
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> new SalesforceStageMapping(20, 50, 80, 90));
        assertTrue(exception.getMessage().contains("exactly 99"));
    }
}
