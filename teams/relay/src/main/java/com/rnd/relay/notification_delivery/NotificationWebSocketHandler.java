package com.rnd.relay.notification_delivery;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
class NotificationWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketNotificationRegistry registry;

    NotificationWebSocketHandler(WebSocketNotificationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String orgId = (String) session.getAttributes().get(NotificationHandshakeInterceptor.ORG_ID_ATTRIBUTE);
        String userId = (String) session.getAttributes().get(NotificationHandshakeInterceptor.USER_ID_ATTRIBUTE);
        Long lastEventId = (Long) session.getAttributes().get(NotificationHandshakeInterceptor.LAST_EVENT_ID_ATTRIBUTE);
        registry.register(session, orgId, userId, lastEventId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Client frames are ignored: identity and the resume token are both resolved at handshake
        // time (design doc §07/§08), and this channel is one-directional in practice today.
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        registry.unregister(session);
    }
}
