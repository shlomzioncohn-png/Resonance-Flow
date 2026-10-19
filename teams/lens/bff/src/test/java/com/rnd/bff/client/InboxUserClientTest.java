package com.rnd.bff.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Wire format against Inbox — Inbox's JSON is snake_case
 * (spring.jackson.property-naming-strategy: SNAKE_CASE), both what it
 * returns and what it reads. The service tests mock this class, so this is
 * the only place that would catch a camelCase key slipping through.
 */
class InboxUserClientTest {

    private static final String INBOX = "http://inbox-test";
    private static final String ACTOR_ID = "11111111-1111-1111-1111-111111111111";

    private MockRestServiceServer inbox;
    private InboxUserClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        inbox = MockRestServiceServer.bindTo(restTemplate).build();
        client = new InboxUserClient(restTemplate);
        ReflectionTestUtils.setField(client, "inboxServiceUrl", INBOX);
    }

    private static final String USER_JSON = """
            {"id":"u1","display_name":"Noa Levi","first_name":"Noa","last_name":"Levi",
             "email":"noa@example.com","username":"noa","role":"EMPLOYEE","status":"PENDING",
             "tenant":{"id":"t1","name":"Acme","slug":"acme"},
             "last_login_at":"2026-10-01T08:30:00Z"}""";

    @Test
    void listUsers_readsInboxsSnakeCaseResponse() {
        inbox.expect(requestTo(INBOX + "/api/v1/users?page=0&size=20"))
                .andExpect(header("X-Actor-Id", ACTOR_ID))
                .andRespond(withSuccess("{\"content\":[" + USER_JSON + "],\"page\":0,\"size\":20,"
                        + "\"total_elements\":41,\"total_pages\":3}", MediaType.APPLICATION_JSON));

        InboxUserPageResponse page = client.listUsers(ACTOR_ID, 0, 20, null);

        assertThat(page.getTotalElements()).isEqualTo(41);
        assertThat(page.getTotalPages()).isEqualTo(3);
        InboxUserRecord user = page.getContent().get(0);
        assertThat(user.getDisplayName()).isEqualTo("Noa Levi");
        assertThat(user.getLastName()).isEqualTo("Levi");
        assertThat(user.getLastLoginAt()).isNotNull();
        assertThat(user.getTenant().getId()).isEqualTo("t1");
    }

    @Test
    void createUser_sendsSnakeCaseKeys() {
        inbox.expect(requestTo(INBOX + "/api/v1/users"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Actor-Id", ACTOR_ID))
                .andExpect(content().json("""
                        {"tenant_id":"t1","display_name":"Noa Levi","first_name":"Noa","role":"EMPLOYEE"}""", true))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(USER_JSON));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", "t1");
        body.put("displayName", "Noa Levi");
        body.put("firstName", "Noa");
        body.put("role", "EMPLOYEE");

        InboxUserRecord created = client.createUser(ACTOR_ID, body);

        assertThat(created.getId()).isEqualTo("u1");
        assertThat(created.getStatus()).isEqualTo("PENDING");
        inbox.verify();
    }

    @Test
    void updateUser_sendsSnakeCaseKeysAndKeepsExplicitNulls() {
        inbox.expect(requestTo(INBOX + "/api/v1/users/u1"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("{\"display_name\":\"Noa\",\"last_name\":null}", true))
                .andRespond(withSuccess(USER_JSON, MediaType.APPLICATION_JSON));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("displayName", "Noa");
        fields.put("lastName", null);

        client.updateUser(ACTOR_ID, "u1", fields);

        inbox.verify();
    }
}
