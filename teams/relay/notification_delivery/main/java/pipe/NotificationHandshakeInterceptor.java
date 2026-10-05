package pipe;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;

@Component
class NotificationHandshakeInterceptor implements HandshakeInterceptor {

    static final String ORG_ID_ATTRIBUTE = "orgId";
    static final String USER_ID_ATTRIBUTE = "userId";
    static final String LAST_EVENT_ID_ATTRIBUTE = "lastEventId";

    private final HeaderAuthenticationStub authenticationStub;

    NotificationHandshakeInterceptor(HeaderAuthenticationStub authenticationStub) {
        this.authenticationStub = authenticationStub;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Optional<AuthenticatedPrincipal> authenticated = authenticationStub.authenticate(
                request.getHeaders().getFirst("X-Org-Id"),
                request.getHeaders().getFirst("X-User-Id"));
        if (authenticated.isEmpty()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        AuthenticatedPrincipal principal = authenticated.get();
        attributes.put(ORG_ID_ATTRIBUTE, principal.orgId());
        attributes.put(USER_ID_ATTRIBUTE, principal.userId());
        String lastEventId = request.getHeaders().getFirst("Last-Event-ID");
        if (lastEventId == null && request instanceof ServletServerHttpRequest servletRequest) {
            lastEventId = servletRequest.getServletRequest().getParameter("lastEventId");
        }
        if (lastEventId != null) {
            try {
                attributes.put(LAST_EVENT_ID_ATTRIBUTE, Long.parseLong(lastEventId));
            } catch (NumberFormatException e) {
                response.setStatusCode(HttpStatus.BAD_REQUEST);
                return false;
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
    }
}
