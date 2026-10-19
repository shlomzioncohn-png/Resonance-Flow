package com.rnd.bridge.crm.repository.salesforce;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.crm.CrmLane;
import com.rnd.bridge.crm.CrmSourceReader;
import com.rnd.bridge.crm.CrmTaskValidator;
import com.rnd.bridge.crm.mapper.DealToTaskMapper;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.NoOpTaskEnricher;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The real pipeline - {@link SalesforceDealRepository} (MockWebServer) -> {@code
 * DealToTaskMapper} -> {@code CrmTaskValidator} -> a capturing publisher - run end to end, the
 * same assembly {@code CrmLaneConfig} wires under the {@code salesforce} profile. No live
 * Salesforce calls. See {@code B5} in {@code teams/bridge/docs/crm-salesforce-design.md} §8.
 * <p>
 * Reuses {@link SalesforceDealRepositoryTest}'s package-private SOQL fixture builders
 * ({@code queryPage}/{@code opportunityRow}/{@code DEFAULT_STAGES}) rather than duplicating the
 * response shape a second time.
 */
class CrmSalesforceLaneIntegrationTest {

    private static final String VALID_TENANT_ID = "11111111-1111-1111-1111-111111111111";
    private static final Instant FIXED_NOW = Instant.parse("2026-08-30T12:00:00Z");

    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    private void enqueueJson(String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private String tokenResponse() {
        return """
                {"access_token": "test-access-token", "instance_url": "%s", "token_type": "Bearer"}
                """.formatted(server.url("/").toString());
    }

    private SalesforceDealRepository newRepository() {
        SalesforceClient client = new SalesforceClient(
                RestClient.builder(),
                server.url("/").toString(),
                "test-client-id",
                "test-client-secret",
                "68.0",
                5,
                0L,
                0L,
                millis -> { });
        return new SalesforceDealRepository(
                client, Clock.fixed(FIXED_NOW, ZoneOffset.UTC), "LastStageChangeDate", false,
                new SalesforceStageMapping(20, 50, 80, 99));
    }

    private static final class CapturingPublisher implements PayloadPublisher<Task> {
        private final List<Task> published = new ArrayList<>();

        @Override
        public void publish(Task payload) {
            published.add(payload);
        }
    }

    @Test
    void everyOpportunityMapsToOneTaskWithTheRightStatusUrgencyDeadlineAndSourceRef() {
        enqueueJson(tokenResponse());
        enqueueJson(SalesforceDealRepositoryTest.DEFAULT_STAGES);
        String open = SalesforceDealRepositoryTest.opportunityRow("SF1", "Open deal", null, "Qualification",
                "5000", "2026-10-15", "2026-05-01T08:00:00.000+0000", "LastStageChangeDate",
                "2026-08-20T10:00:00.000+0000", null, null);
        String won = SalesforceDealRepositoryTest.opportunityRow("SF2", "Won deal", null, "Closed Won",
                "8000", "2026-08-20", "2026-05-01T08:00:00.000+0000", "LastStageChangeDate",
                "2026-08-20T10:00:00.000+0000", null, null);
        String lost = SalesforceDealRepositoryTest.opportunityRow("SF3", "Lost deal", null, "Closed Lost",
                "3000", "2026-08-25", "2026-05-01T08:00:00.000+0000", "LastStageChangeDate",
                "2026-08-20T10:00:00.000+0000", null, null);
        // Not in DEFAULT_STAGES - an unresolvable StageName, skipped at the repository read
        // rather than aborting the whole sync (mirrors SalesforceDealRepositoryTest's own case).
        String unresolvable = SalesforceDealRepositoryTest.opportunityRow("SF4", "Bad stage deal", null,
                "Some Deleted Custom Stage", "1000", "2026-09-01", "2026-05-01T08:00:00.000+0000",
                "LastStageChangeDate", "2026-08-20T10:00:00.000+0000", null, null);
        enqueueJson(SalesforceDealRepositoryTest.queryPage(open, won, lost, unresolvable));

        SalesforceDealRepository repository = newRepository();
        CapturingPublisher publisher = new CapturingPublisher();
        CrmLane lane = new CrmLane(
                new CrmSourceReader(repository),
                new DealToTaskMapper(VALID_TENANT_ID, new EnrichmentProperties(), new NoOpTaskEnricher(), 0),
                new CrmTaskValidator(new TaskValidator()),
                publisher);

        IntakeResult result = lane.run(ReadCursor.full(), FIXED_NOW);

        assertEquals(3, result.read(),
                "SF4's unresolvable stage is skipped at the repository read, never reaching the lane");
        assertEquals(3, result.published());
        assertEquals(0, result.rejected(), () -> "expected no rejections, got: " + result.errors());
        assertEquals(3, publisher.published.size(), "one Task per Opportunity");

        Map<String, Task> bySourceRef = new HashMap<>();
        for (Task task : publisher.published) {
            bySourceRef.put(task.sourceRef(), task);
        }
        assertEquals(Set.of("crm:salesforce:SF1", "crm:salesforce:SF2", "crm:salesforce:SF3"), bySourceRef.keySet());

        Task openTask = bySourceRef.get("crm:salesforce:SF1");
        Task wonTask = bySourceRef.get("crm:salesforce:SF2");
        Task lostTask = bySourceRef.get("crm:salesforce:SF3");

        assertEquals(TaskStatus.NEW, openTask.status());
        assertEquals(2, openTask.urgency(), "QUALIFIED base=2, close date 46 days out gets no bonus");
        assertEquals(Instant.parse("2026-10-15T23:59:59Z"), openTask.deadline());

        assertEquals(TaskStatus.DONE, wonTask.status());
        assertEquals(Instant.parse("2026-08-20T23:59:59Z"), wonTask.deadline());
        assertEquals(wonTask.deadline(), wonTask.completedAt(), "a won deal's completedAt mirrors its deadline");

        assertEquals(TaskStatus.CANCELLED, lostTask.status());
        assertEquals(Instant.parse("2026-08-25T23:59:59Z"), lostTask.deadline());
        assertNull(lostTask.completedAt(), "a lost deal never completed");
    }
}
