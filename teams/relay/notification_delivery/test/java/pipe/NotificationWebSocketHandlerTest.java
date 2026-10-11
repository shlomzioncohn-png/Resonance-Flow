package pipe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationWebSocketHandlerTest {

    @Mock
    WebSocketNotificationRegistry registry;

    @Mock
    WebSocketSession session;

    private NotificationWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new NotificationWebSocketHandler(registry);
    }

    @Test
    void establishedConnectionIsRegisteredWithTheAttributesFromTheHandshake() {
        when(session.getAttributes()).thenReturn(Map.of(
                NotificationHandshakeInterceptor.ORG_ID_ATTRIBUTE, "org-1",
                NotificationHandshakeInterceptor.USER_ID_ATTRIBUTE, "user-1",
                NotificationHandshakeInterceptor.LAST_EVENT_ID_ATTRIBUTE, 9L));

        handler.afterConnectionEstablished(session);

        verify(registry).register(session, "org-1", "user-1", 9L);
    }

    @Test
    void establishedConnectionWithoutAResumeTokenRegistersWithNullLastEventId() {
        when(session.getAttributes()).thenReturn(Map.of(
                NotificationHandshakeInterceptor.ORG_ID_ATTRIBUTE, "org-1",
                NotificationHandshakeInterceptor.USER_ID_ATTRIBUTE, "user-1"));

        handler.afterConnectionEstablished(session);

        verify(registry).register(eq(session), eq("org-1"), eq("user-1"), isNull());
    }

    @Test
    void cleanCloseUnregistersTheConnection() {
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(registry).unregister(session);
    }

    @Test
    void transportErrorUnregistersTheConnection() {
        handler.handleTransportError(session, new RuntimeException("broken pipe"));

        verify(registry).unregister(session);
    }

    @Test
    void inboundClientFramesAreIgnored() throws Exception {
        handler.handleTextMessage(session, new org.springframework.web.socket.TextMessage("ignored"));

        verifyNoInteractions(registry);
    }
}
