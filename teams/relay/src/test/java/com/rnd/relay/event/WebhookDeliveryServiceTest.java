package com.rnd.relay.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookDeliveryServiceTest {

    private RestClient.Builder restClientBuilder;
    private RestClient restClient;

    private WebhookDeliveryService service;

    @BeforeEach
    void setUp() {
        restClientBuilder = mock(RestClient.Builder.class);
        restClient = mock(RestClient.class);

        when(restClientBuilder.build()).thenReturn(restClient);
        service = new WebhookDeliveryService(restClientBuilder, "https://example.com/webhook");
    }

    @Test
    void deliverEvent_handlesRestClientExceptionGracefully() {
        when(restClient.post()).thenThrow(new RuntimeException("network error"));

        assertDoesNotThrow(() -> service.deliverEvent(new Object()));

        verify(restClientBuilder).build();
        verify(restClient).post();
    }
}
