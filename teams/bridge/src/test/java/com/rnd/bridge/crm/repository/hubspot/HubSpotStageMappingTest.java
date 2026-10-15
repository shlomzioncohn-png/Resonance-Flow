package com.rnd.bridge.crm.repository.hubspot;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.crm.model.DealStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests of {@link HubSpotStageMapping#resolveIdsToStages} - stage metadata in, {@link
 * StageResolution} out, no HTTP, no {@code HubSpotDealRepository}. {@code
 * HubSpotDealRepositoryTest} covers the integration-level behavior this feeds into (constructor
 * fail/warn, per-deal skip + the skip-ratio threshold, {@code fetchDeal}).
 */
class HubSpotStageMappingTest {

    private static final HubSpotStageMapping DEFAULT_BANDS = new HubSpotStageMapping(20, 50, 70, 99);

    private final List<ListAppender<ILoggingEvent>> attachedAppenders = new java.util.ArrayList<>();

    @AfterEach
    void detachLogCaptures() {
        Logger logger = (Logger) LoggerFactory.getLogger(HubSpotStageMapping.class);
        attachedAppenders.forEach(logger::detachAppender);
        attachedAppenders.clear();
    }

    private ListAppender<ILoggingEvent> captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(HubSpotStageMapping.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        attachedAppenders.add(appender);
        return appender;
    }

    private static HubSpotPipeline pipeline(HubSpotPipelineStage... stages) {
        return new HubSpotPipeline("pl-1", "Sales Pipeline", List.of(stages));
    }

    private static HubSpotPipelineStage stage(String id, String label, String isClosed, String probability) {
        return new HubSpotPipelineStage(id, label, new HubSpotStageMetadata(isClosed, probability));
    }

    private static HubSpotPipelineStage stageNoMetadata(String id, String label) {
        return new HubSpotPipelineStage(id, label, null);
    }

    // ---- closed stages: BigDecimal.compareTo, never equals, against exactly 0/1 ----

    @Test
    void closedStageAtProbabilityOneMapsToClosedWonRegardlessOfTrailingZeros() {
        for (String one : List.of("1", "1.0", "1.00")) {
            StageResolution resolution =
                    DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(stage("s1", "Closed Won", "true", one))));
            assertEquals(DealStage.CLOSED_WON, resolution.idToStage().get("s1"), "probability \"" + one + "\"");
        }
    }

    @Test
    void closedStageAtProbabilityZeroMapsToClosedLostRegardlessOfTrailingZeros() {
        for (String zero : List.of("0", "0.0")) {
            StageResolution resolution =
                    DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(stage("s1", "Closed Lost", "true", zero))));
            assertEquals(DealStage.CLOSED_LOST, resolution.idToStage().get("s1"), "probability \"" + zero + "\"");
        }
    }

    @Test
    void closedStageWithNonBoundaryProbabilityIsInvalid() {
        // A companion always-valid stage keeps this a "one bad stage, isolate it" case rather than
        // tripping the separate "every stage invalid" FAIL path (see metadataMissingOnEveryStage...).
        StageResolution resolution = DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(
                stage("s1", "Ambiguous", "true", "0.5"),
                stage("ok", "Always fine", "false", "0.1"))));

        assertFalse(resolution.idToStage().containsKey("s1"));
        assertTrue(resolution.invalidStageReasons().get("s1").contains("neither 0 nor 1"));
    }

    @Test
    void closedStageWithMissingProbabilityIsInvalid() {
        StageResolution resolution = DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(
                stage("s1", "No probability", "true", null),
                stage("ok", "Always fine", "false", "0.1"))));

        assertFalse(resolution.idToStage().containsKey("s1"));
        assertTrue(resolution.invalidStageReasons().get("s1").contains("no probability"));
    }

    // ---- open stages: band boundaries, the F6 regression at 0.8, missing probability ----

    @Test
    void openStageBandBoundariesResolveToTheExpectedStage() {
        List<HubSpotPipelineStage> stages = List.of(
                stage("at-20", "At 20", "false", "0.20"),
                stage("at-21", "At 21", "false", "0.21"),
                stage("at-50", "At 50", "false", "0.50"),
                stage("at-51", "At 51", "false", "0.51"),
                stage("at-70", "At 70", "false", "0.70"),
                stage("at-71", "At 71", "false", "0.71"),
                stage("at-99", "At 99", "false", "0.99"));

        StageResolution resolution =
                DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(stages.toArray(new HubSpotPipelineStage[0]))));

        assertEquals(DealStage.PROSPECT, resolution.idToStage().get("at-20"));
        assertEquals(DealStage.QUALIFIED, resolution.idToStage().get("at-21"));
        assertEquals(DealStage.QUALIFIED, resolution.idToStage().get("at-50"));
        assertEquals(DealStage.PROPOSAL, resolution.idToStage().get("at-51"));
        assertEquals(DealStage.PROPOSAL, resolution.idToStage().get("at-70"));
        assertEquals(DealStage.NEGOTIATION, resolution.idToStage().get("at-71"));
        assertEquals(DealStage.NEGOTIATION, resolution.idToStage().get("at-99"));
    }

    @Test
    void probabilityOfEightyMapsToNegotiationNotProposal() {
        // The actual Step 0 correction: HubSpot's own default pipeline has "Decision Maker
        // Bought-In" at probability 0.8, which must land in NEGOTIATION (today's label mapping),
        // not PROPOSAL - the reason HubSpot's bands are 20/50/70/99, not Salesforce's 20/50/80/99.
        StageResolution resolution = DEFAULT_BANDS.resolveIdsToStages(
                List.of(pipeline(stage("s1", "Decision Maker Bought-In", "false", "0.8"))));

        assertEquals(DealStage.NEGOTIATION, resolution.idToStage().get("s1"));
    }

    @Test
    void openStageWithMissingProbabilityDefaultsToProspectAndLogsOneWarnPerStage() {
        ListAppender<ILoggingEvent> logs = captureLogs();

        StageResolution resolution = DEFAULT_BANDS.resolveIdsToStages(
                List.of(pipeline(stage("s1", "No probability set", "false", null))));

        assertEquals(DealStage.PROSPECT, resolution.idToStage().get("s1"));
        assertEquals(1, logs.list.size(), "exactly one WARN for this one stage, not one per deal");
        ILoggingEvent event = logs.list.get(0);
        assertEquals("WARN", event.getLevel().toString());
        String message = event.getFormattedMessage();
        assertTrue(message.contains("pl-1"), "message should name the pipeline id: " + message);
        assertTrue(message.contains("s1"), "message should name the stage id: " + message);
    }

    // ---- invalid probability: never clamped, never silently defaulted ----

    @Test
    void nonNumericOrOutOfRangeOrNaNProbabilityOnAnOpenStageIsInvalidNeverClampedNeverDefaulted() {
        for (String bad : List.of("abc", "", "-0.1", "1.5", "NaN")) {
            StageResolution resolution = DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(
                    stage("s1", "Bad probability", "false", bad),
                    stage("ok", "Always fine", "false", "0.1"))));

            assertFalse(resolution.idToStage().containsKey("s1"), "probability \"" + bad + "\" must not map");
            assertTrue(resolution.invalidStageReasons().containsKey("s1"), "probability \"" + bad + "\" must be invalid");
        }
    }

    @Test
    void nonNumericProbabilityOnAClosedStageIsInvalid() {
        StageResolution resolution = DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(
                stage("s1", "Bad closed probability", "true", "abc"),
                stage("ok", "Always fine", "false", "0.1"))));

        assertFalse(resolution.idToStage().containsKey("s1"));
        assertTrue(resolution.invalidStageReasons().get("s1").contains("not a valid number"));
    }

    // ---- isClosed leniency and strictness ----

    @Test
    void isClosedMissingIsInvalid() {
        StageResolution resolution = DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(
                stage("s1", "No isClosed", null, "0.5"),
                stage("ok", "Always fine", "false", "0.1"))));

        assertFalse(resolution.idToStage().containsKey("s1"));
        assertTrue(resolution.invalidStageReasons().get("s1").contains("isClosed"));
    }

    @Test
    void isClosedNotARecognizedBooleanValueIsInvalid() {
        for (String notBoolean : List.of("maybe", "1", "yes")) {
            StageResolution resolution = DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(
                    stage("s1", "Weird isClosed", notBoolean, "0.5"),
                    stage("ok", "Always fine", "false", "0.1"))));

            assertTrue(resolution.invalidStageReasons().containsKey("s1"), "isClosed \"" + notBoolean + "\" must be invalid");
        }
    }

    @Test
    void jsonBooleanAndNumberMetadataCoerceToStringsJustLikeJsonStrings() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String rawScalars = """
                {"id": "s1", "label": "Raw scalars", "metadata": {"isClosed": false, "probability": 0.4}}
                """;
        String quotedScalars = """
                {"id": "s2", "label": "Quoted scalars", "metadata": {"isClosed": "false", "probability": "0.4"}}
                """;

        HubSpotPipelineStage fromRaw = mapper.readValue(rawScalars, HubSpotPipelineStage.class);
        HubSpotPipelineStage fromQuoted = mapper.readValue(quotedScalars, HubSpotPipelineStage.class);

        StageResolution rawResolution = DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(fromRaw)));
        StageResolution quotedResolution = DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(fromQuoted)));

        assertEquals(DealStage.QUALIFIED, rawResolution.idToStage().get("s1"));
        assertEquals(DealStage.QUALIFIED, quotedResolution.idToStage().get("s2"));
    }

    // ---- metadata missing on one stage vs. every stage ----

    @Test
    void metadataMissingOnOneStageIsInvalidButOtherStagesStillResolve() {
        StageResolution resolution = DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(
                stageNoMetadata("s1", "No metadata at all"),
                stage("s2", "Fine", "false", "0.2"))));

        assertTrue(resolution.invalidStageReasons().get("s1").contains("no metadata"));
        assertEquals(DealStage.PROSPECT, resolution.idToStage().get("s2"));
    }

    @Test
    void metadataMissingOnEveryStageFailsEntirelyAsALikelyApiShapeChange() {
        UnknownHubSpotStageException exception = assertThrows(UnknownHubSpotStageException.class,
                () -> DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(
                        stageNoMetadata("s1", "No metadata"),
                        stageNoMetadata("s2", "Also no metadata")))));

        assertTrue(exception.getMessage().contains("shape change"));
    }

    // ---- stages that can't be isolated to a per-deal skip ----

    @Test
    void stageWithNoIdFailsEntirely() {
        HubSpotPipelineStage noId = new HubSpotPipelineStage(null, "Mystery stage",
                new HubSpotStageMetadata("false", "0.2"));

        UnknownHubSpotStageException exception = assertThrows(UnknownHubSpotStageException.class,
                () -> DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(noId))));

        assertTrue(exception.getMessage().contains("Mystery stage"));
    }

    @Test
    void zeroStagesFetchedFailsEntirely() {
        assertThrows(UnknownHubSpotStageException.class,
                () -> DEFAULT_BANDS.resolveIdsToStages(List.of()));
    }

    // ---- renamed/custom labels: the actual F6 fix ----

    @Test
    void renamedLabelWithValidMetadataStillMapsToNegotiation() {
        StageResolution resolution = DEFAULT_BANDS.resolveIdsToStages(
                List.of(pipeline(stage("s1", "Contract Out", "false", "0.9"))));

        assertEquals(DealStage.NEGOTIATION, resolution.idToStage().get("s1"));
    }

    @Test
    void customPipelineWithCustomLabelsMapsByProbabilityNotByLabel() {
        StageResolution resolution = DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(
                stage("c1", "Top of Funnel", "false", "0.1"),
                stage("c2", "Mid Funnel", "false", "0.6"),
                stage("c3", "Signed", "true", "1.0"),
                stage("c4", "Churned", "true", "0.0"))));

        assertEquals(DealStage.PROSPECT, resolution.idToStage().get("c1"));
        assertEquals(DealStage.PROPOSAL, resolution.idToStage().get("c2"));
        assertEquals(DealStage.CLOSED_WON, resolution.idToStage().get("c3"));
        assertEquals(DealStage.CLOSED_LOST, resolution.idToStage().get("c4"));
    }

    @Test
    void defaultSevenStagePipelineMapsExactlyLikeTodaysLabelMapping() {
        // The probabilities Step 0 confirmed against the live default pipeline.
        StageResolution resolution = DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(
                stage("appointmentscheduled", "Appointment Scheduled", "false", "0.2"),
                stage("qualifiedtobuy", "Qualified To Buy", "false", "0.4"),
                stage("presentationscheduled", "Presentation Scheduled", "false", "0.6"),
                stage("decisionmakerboughtin", "Decision Maker Bought-In", "false", "0.8"),
                stage("contractsent", "Contract Sent", "false", "0.9"),
                stage("closedwon", "Closed Won", "true", "1.0"),
                stage("closedlost", "Closed Lost", "true", "0.0"))));

        assertEquals(DealStage.PROSPECT, resolution.idToStage().get("appointmentscheduled"));
        assertEquals(DealStage.QUALIFIED, resolution.idToStage().get("qualifiedtobuy"));
        assertEquals(DealStage.PROPOSAL, resolution.idToStage().get("presentationscheduled"));
        assertEquals(DealStage.NEGOTIATION, resolution.idToStage().get("decisionmakerboughtin"));
        assertEquals(DealStage.NEGOTIATION, resolution.idToStage().get("contractsent"));
        assertEquals(DealStage.CLOSED_WON, resolution.idToStage().get("closedwon"));
        assertEquals(DealStage.CLOSED_LOST, resolution.idToStage().get("closedlost"));
    }

    // ---- a deal referencing a stage id absent from the resolved map ----

    @Test
    void aStageIdAbsentFromEitherMapIsALookupMissNotAnException() {
        StageResolution resolution =
                DEFAULT_BANDS.resolveIdsToStages(List.of(pipeline(stage("s1", "Known", "false", "0.2"))));

        assertFalse(resolution.idToStage().containsKey("does-not-exist"));
        assertFalse(resolution.invalidStageReasons().containsKey("does-not-exist"));
    }

    // ---- bands config validation (shared StageProbabilityBands, mirrors SalesforceStageMappingTest) ----

    @Test
    void qualifiedMaxNotGreaterThanProspectMaxFailsAtConstruction() {
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> new HubSpotStageMapping(20, 20, 70, 99));
        assertTrue(exception.getMessage().contains("qualified-max"));
    }

    @Test
    void negotiationMaxNotExactlyNinetyNineFailsAtConstruction() {
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> new HubSpotStageMapping(20, 50, 70, 90));
        assertTrue(exception.getMessage().contains("exactly 99"));
    }
}
