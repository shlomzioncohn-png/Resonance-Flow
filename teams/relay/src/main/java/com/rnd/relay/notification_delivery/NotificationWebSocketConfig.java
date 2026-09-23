package com.rnd.relay.notification_delivery;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
class NotificationWebSocketConfig implements WebSocketConfigurer {

    private final NotificationWebSocketHandler handler;
    private final NotificationHandshakeInterceptor handshakeInterceptor;

    NotificationWebSocketConfig(NotificationWebSocketHandler handler, NotificationHandshakeInterceptor handshakeInterceptor) {
        this.handler = handler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/events/stream")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
