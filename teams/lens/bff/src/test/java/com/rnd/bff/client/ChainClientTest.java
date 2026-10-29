package com.rnd.bff.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rnd.bff.dto.ConstraintDTO;
import com.rnd.bff.dto.TaskDependenciesDTO;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

/** The wire format of the Chain calls (task histories, constraints) - above all, that X-Tenant-Id is always sent. */
class ChainClientTest {

    private static final String CHAIN = "http://chain.test";
    private static final String TASK = "7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c01";
    private static final String TENANT = "aaaaaaaa-0000-0000-0000-000000000001";

    private MockRestServiceServer chain;
    private ChainClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        // Constraint calls run in parallel, so they may arrive in any order.
        chain = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        client = new ChainClient(restTemplate);
        ReflectionTestUtils.setField(client, "chainServiceUrl", CHAIN);
    }

    @Test
    void stateHistorySendsTheTenantHeaderAndPageSize50() {
        chain.expect(requestTo(CHAIN + "/tasks/" + TASK + "/state-history?page=0&size=50"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Tenant-Id", TENANT))
                .andRespond(withSuccess("""
                        {"content":[
                          {"id":"s1","taskId":"%s","eventType":"STATUS_CHANGED","fromStatus":"PENDING","toStatus":"ACTIVE","createdAt":"2026-10-20T09:15:30Z"},
                          {"id":"s2","taskId":"%s","eventType":"BLOCKED","blockingCountBefore":0,"blockingCountAfter":2,"createdAt":"2026-10-19T14:02:11Z"}
                        ],"page":0,"size":50,"totalElements":2,"totalPages":1}
                        """.formatted(TASK, TASK), MediaType.APPLICATION_JSON));

        List<ChainStateHistoryEntry> entries = client.getStateHistory(TASK, TENANT);

        chain.verify();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).eventType()).isEqualTo("STATUS_CHANGED");
        assertThat(entries.get(0).fromStatus()).isEqualTo("PENDING");
        assertThat(entries.get(0).createdAt()).isEqualTo(Instant.parse("2026-10-20T09:15:30Z"));
        // Fields Chain leaves out for this event type stay null.
        assertThat(entries.get(0).blockingCountAfter()).isNull();
        assertThat(entries.get(1).blockingCountAfter()).isEqualTo(2);
        assertThat(entries.get(1).toStatus()).isNull();
    }

    @Test
    void dependencyHistorySendsTheTenantHeaderAndPageSize50() {
        chain.expect(requestTo(CHAIN + "/tasks/" + TASK + "/dependencies/history?page=0&size=50"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Tenant-Id", TENANT))
                .andRespond(withSuccess("""
                        {"content":[
                          {"id":"d1","taskId":"%s","dependsOnTaskId":"t-9","action":"ADDED","source":"USER","sourceRef":null,"createdAt":"2026-10-18T11:40:00Z"}
                        ],"page":0,"size":50,"totalElements":1,"totalPages":1}
                        """.formatted(TASK), MediaType.APPLICATION_JSON));

        List<ChainDependencyHistoryEntry> entries = client.getDependencyHistory(TASK, TENANT);

        chain.verify();
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("ADDED");
            assertThat(entry.source()).isEqualTo("USER");
            assertThat(entry.dependsOnTaskId()).isEqualTo("t-9");
        });
    }

    @Test
    void notFoundMeansNoHistory() {
        // Chain answers 404 both for "not in this tenant" and "doesn't exist".
        chain.expect(requestTo(CHAIN + "/tasks/" + TASK + "/state-history?page=0&size=50"))
                .andExpect(header("X-Tenant-Id", TENANT))
                .andRespond(withResourceNotFound());

        assertThat(client.getStateHistory(TASK, TENANT)).isEmpty();
    }

    @Test
    void badRequestIsAnError() {
        // 400 = missing or invalid X-Tenant-Id: a real failure, not "no history".
        chain.expect(requestTo(CHAIN + "/tasks/" + TASK + "/dependencies/history?page=0&size=50"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> client.getDependencyHistory(TASK, TENANT))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    void feasibilityFetchesConstraintsPerTaskWithOnlyTheTenantHeader() {
        chain.expect(requestTo(CHAIN + "/tasks/" + TASK + "/constraints"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Tenant-Id", TENANT))
                // Chain reads no identity header besides the tenant
                .andExpect(headerDoesNotExist("X-Actor-Id"))
                .andRespond(withSuccess("""
                        [
                          {"constraintId":"c1","taskId":"%s","type":"APPROVAL_NEEDED","status":"PENDING",
                           "reason":{"note":"Legal sign-off"},"sourceRef":null,
                           "createdAt":"2026-10-20T09:15:30","updatedAt":"2026-10-20T09:15:30"},
                          {"constraintId":"c2","taskId":"%s","type":"DATE_WINDOW","status":"SATISFIED",
                           "reason":{},"createdAt":"2026-10-21T10:00:00"}
                        ]
                        """.formatted(TASK, TASK), MediaType.APPLICATION_JSON));

        Map<String, TaskDependenciesDTO> result = client.getFeasibility(TENANT, List.of(TASK));

        chain.verify();
        TaskDependenciesDTO feasibility = result.get(TASK);
        assertThat(feasibility.getDependencies()).isEmpty();
        assertThat(feasibility.getConstraints()).hasSize(2);
        ConstraintDTO first = feasibility.getConstraints().get(0);
        assertThat(first.getConstraintId()).isEqualTo("c1");
        assertThat(first.getType()).isEqualTo("APPROVAL_NEEDED");
        assertThat(first.getStatus()).isEqualTo("PENDING");
        assertThat(first.getReason()).containsEntry("note", "Legal sign-off");
        assertThat(first.getCreatedAt()).isEqualTo("2026-10-20T09:15:30");
    }

    @Test
    void feasibilityGivesAFailingTaskAnEmptyListWithoutFailingTheOthers() {
        String other = "7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c02";
        String missing = "7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c03";
        chain.expect(requestTo(CHAIN + "/tasks/" + TASK + "/constraints")).andRespond(withServerError());
        chain.expect(requestTo(CHAIN + "/tasks/" + other + "/constraints"))
                .andRespond(withSuccess("""
                        [{"constraintId":"c9","type":"BLOCKED_BY_INFO","status":"PENDING","reason":{}}]
                        """, MediaType.APPLICATION_JSON));
        chain.expect(requestTo(CHAIN + "/tasks/" + missing + "/constraints")).andRespond(withResourceNotFound());

        Map<String, TaskDependenciesDTO> result =
                client.getFeasibility(TENANT, List.of(TASK, other, missing));

        chain.verify();
        assertThat(result).containsOnlyKeys(TASK, other, missing);
        assertThat(result.get(TASK).getConstraints()).isEmpty();
        assertThat(result.get(other).getConstraints()).extracting(ConstraintDTO::getConstraintId).containsExactly("c9");
        assertThat(result.get(missing).getConstraints()).isEmpty();
    }

    @Test
    void feasibilityWithChainReturningAnEmptyListGivesNoConstraints() {
        chain.expect(requestTo(CHAIN + "/tasks/" + TASK + "/constraints"))
                .andExpect(header("X-Tenant-Id", TENANT))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        Map<String, TaskDependenciesDTO> result = client.getFeasibility(TENANT, List.of(TASK));

        chain.verify();
        assertThat(result.get(TASK).getConstraints()).isNotNull().isEmpty();
        assertThat(ConstraintDTO.countOpen(result.get(TASK).getConstraints())).isZero();
    }

    @Test
    void feasibilityWithChainFailingGivesAnEmptyListInsteadOfThrowing() {
        chain.expect(requestTo(CHAIN + "/tasks/" + TASK + "/constraints")).andRespond(withBadRequest());

        Map<String, TaskDependenciesDTO> result = client.getFeasibility(TENANT, List.of(TASK));

        chain.verify();
        assertThat(result.get(TASK).getConstraints()).isEmpty();
    }

    @Test
    void feasibilityWithoutATenantMakesNoCallsAndShowsNoConstraints() {
        Map<String, TaskDependenciesDTO> result = client.getFeasibility(null, List.of(TASK));

        chain.verify(); // no expectations -> fails if any request was made
        assertThat(result.get(TASK).getConstraints()).isEmpty();
    }

    @Test
    void serverErrorIsAnError() {
        chain.expect(requestTo(CHAIN + "/tasks/" + TASK + "/state-history?page=0&size=50"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getStateHistory(TASK, TENANT))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void dependencyGraphAsksForTheTenantsGraphWithLimit500() {
        chain.expect(requestTo(CHAIN + "/tenants/" + TENANT + "/dependency-graph?limit=500"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Tenant-Id", TENANT))
                .andRespond(withSuccess("""
                        {"nodes":[
                          {"taskId":"t-1","status":"ACTIVE","isReady":true,"blockingCount":0,"transitiveDependentsCount":3,"blockedSince":null},
                          {"taskId":"t-2","status":"PENDING","isReady":false,"blockingCount":1,"transitiveDependentsCount":0,"blockedSince":"2026-10-19T14:02:11Z"}
                        ],"edges":[{"taskId":"t-2","dependsOnTaskId":"t-1"}],"hasMore":false}
                        """, MediaType.APPLICATION_JSON));

        ChainDependencyGraph graph = client.getDependencyGraph(TENANT);

        chain.verify();
        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.nodes().get(0).isReady()).isTrue();
        assertThat(graph.nodes().get(0).transitiveDependentsCount()).isEqualTo(3);
        assertThat(graph.nodes().get(1).isReady()).isFalse();
        assertThat(graph.nodes().get(1).blockedSince()).isEqualTo(Instant.parse("2026-10-19T14:02:11Z"));
        assertThat(graph.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.taskId()).isEqualTo("t-2");
            assertThat(edge.dependsOnTaskId()).isEqualTo("t-1");
            // Chain doesn't send it yet
            assertThat(edge.confidence()).isNull();
        });
    }

    @Test
    void dependencyGraphServerErrorIsAnError() {
        chain.expect(requestTo(CHAIN + "/tenants/" + TENANT + "/dependency-graph?limit=500"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getDependencyGraph(TENANT))
                .isInstanceOf(HttpServerErrorException.class);
    }
}
