package com.rnd.bff.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rnd.bff.dto.ChainFlowHealthFlagDTO;
import com.rnd.bff.exception.ServiceUnavailableException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** The flow-health call of ChainClient: paging, JSON mapping, and the 503 on any Chain failure. */
class ChainFlowHealthClientTest {

    private static final String BASE = "http://chain.test";

    private MockRestServiceServer server;
    private ChainClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new ChainClient(restTemplate);
        ReflectionTestUtils.setField(client, "chainServiceUrl", BASE);
    }

    private static String page(int page, int totalPages, String... flagIds) {
        StringBuilder content = new StringBuilder();
        for (String id : flagIds) {
            if (content.length() > 0) {
                content.append(',');
            }
            content.append("{\"flagId\":\"").append(id).append("\",\"pattern\":\"SLOW_CHAIN\",\"entityType\":\"chain\","
                    + "\"entityIds\":[\"t1\",\"t2\"],\"severity\":\"high\",\"score\":0.9,\"rank\":1,"
                    + "\"explanation\":\"slow\",\"evidence\":{\"days\":12},\"runAt\":\"2026-10-28T10:00:00Z\","
                    + "\"somethingNew\":true}");
        }
        return "{\"content\":[" + content + "],\"page\":" + page + ",\"size\":100,\"totalElements\":2,\"totalPages\":"
                + totalPages + "}";
    }

    @Test
    void getFlowHealthFlags_mapsChainsJsonIgnoringUnknownFields() {
        server.expect(requestTo(BASE + "/flow-health/flags?page=0&size=100"))
                .andRespond(withSuccess(page(0, 1, "f1"), MediaType.APPLICATION_JSON));

        List<ChainFlowHealthFlagDTO> flags = client.getFlowHealthFlags();

        assertThat(flags).hasSize(1);
        ChainFlowHealthFlagDTO flag = flags.get(0);
        assertThat(flag.getFlagId()).isEqualTo("f1");
        assertThat(flag.getEntityType()).isEqualTo("chain");
        assertThat(flag.getEntityIds()).containsExactly("t1", "t2");
        assertThat(flag.getEvidence().get("days").asInt()).isEqualTo(12);
        server.verify();
    }

    @Test
    void getFlowHealthFlags_readsEveryPage() {
        server.expect(queryParam("page", "0")).andRespond(withSuccess(page(0, 2, "f1"), MediaType.APPLICATION_JSON));
        server.expect(queryParam("page", "1")).andRespond(withSuccess(page(1, 2, "f2"), MediaType.APPLICATION_JSON));

        assertThat(client.getFlowHealthFlags()).extracting(ChainFlowHealthFlagDTO::getFlagId)
                .containsExactly("f1", "f2");
        server.verify();
    }

    @Test
    void getFlowHealthFlags_chainError_becomesServiceUnavailableWithoutInternalDetails() {
        server.expect(requestTo(BASE + "/flow-health/flags?page=0&size=100")).andRespond(withServerError());

        assertThatThrownBy(() -> client.getFlowHealthFlags())
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageNotContaining("500")
                .hasMessageNotContaining(BASE);
    }
}
