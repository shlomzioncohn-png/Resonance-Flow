package com.rnd.bff.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

/** The wire format of the two task-history calls - above all, that X-Tenant-Id is always sent. */
class ChainClientTest {

    private static final String CHAIN = "http://chain.test";
    private static final String TASK = "7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c01";
    private static final String TENANT = "aaaaaaaa-0000-0000-0000-000000000001";

    private MockRestServiceServer chain;
    private ChainClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        chain = MockRestServiceServer.bindTo(restTemplate).build();
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
    void serverErrorIsAnError() {
        chain.expect(requestTo(CHAIN + "/tasks/" + TASK + "/state-history?page=0&size=50"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getStateHistory(TASK, TENANT))
                .isInstanceOf(HttpServerErrorException.class);
    }
}
