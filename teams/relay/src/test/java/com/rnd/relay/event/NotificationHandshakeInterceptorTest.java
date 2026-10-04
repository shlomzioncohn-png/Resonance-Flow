package com.rnd.relay.event;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationHandshakeInterceptorTest {

    private final NotificationHandshakeInterceptor interceptor =
            new NotificationHandshakeInterceptor(new HeaderAuthenticationStub());

    @Test
    void missingOrgIdIsRejectedWithBadRequest() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/api/v1/events/stream");
        servletRequest.addHeader("X-User-Id", "user-1");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = beforeHandshake(servletRequest, attributes);

        assertThat(accepted).isFalse();
        assertThat(attributes).isEmpty();
    }

    @Test
    void missingUserIdIsRejectedWithBadRequest() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/api/v1/events/stream");
        servletRequest.addHeader("X-Org-Id", "org-1");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = beforeHandshake(servletRequest, attributes);

        assertThat(accepted).isFalse();
    }

    @Test
    void validHeadersResolveOrgAndUserWithNoResumeToken() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/api/v1/events/stream");
        servletRequest.addHeader("X-Org-Id", "org-1");
        servletRequest.addHeader("X-User-Id", "user-1");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = beforeHandshake(servletRequest, attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes)
                .containsEntry(NotificationHandshakeInterceptor.ORG_ID_ATTRIBUTE, "org-1")
                .containsEntry(NotificationHandshakeInterceptor.USER_ID_ATTRIBUTE, "user-1")
                .doesNotContainKey(NotificationHandshakeInterceptor.LAST_EVENT_ID_ATTRIBUTE);
    }

    @Test
    void lastEventIdHeaderIsParsedAsTheResumeToken() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/api/v1/events/stream");
        servletRequest.addHeader("X-Org-Id", "org-1");
        servletRequest.addHeader("X-User-Id", "user-1");
        servletRequest.addHeader("Last-Event-ID", "17");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = beforeHandshake(servletRequest, attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry(NotificationHandshakeInterceptor.LAST_EVENT_ID_ATTRIBUTE, 17L);
    }

    @Test
    void lastEventIdFallsBackToTheQueryParamWhenNoHeaderIsSent() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/api/v1/events/stream");
        servletRequest.addHeader("X-Org-Id", "org-1");
        servletRequest.addHeader("X-User-Id", "user-1");
        servletRequest.addParameter("lastEventId", "23");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = beforeHandshake(servletRequest, attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry(NotificationHandshakeInterceptor.LAST_EVENT_ID_ATTRIBUTE, 23L);
    }

    @Test
    void nonNumericLastEventIdIsRejectedWithBadRequest() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/api/v1/events/stream");
        servletRequest.addHeader("X-Org-Id", "org-1");
        servletRequest.addHeader("X-User-Id", "user-1");
        servletRequest.addHeader("Last-Event-ID", "not-a-number");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = beforeHandshake(servletRequest, attributes);

        assertThat(accepted).isFalse();
    }

    private boolean beforeHandshake(MockHttpServletRequest servletRequest, Map<String, Object> attributes) {
        ServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
        ServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
        return interceptor.beforeHandshake(request, response, null, attributes);
    }
}
