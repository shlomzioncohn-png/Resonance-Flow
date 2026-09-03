package com.rnd.relay.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    
    private final RestClient restClient;
    private final String targetWebhookUrl;

    public WebhookDeliveryService(
            RestClient.Builder restClientBuilder,
            @Value("${relay.webhook.target-url:https://httpbin.org/post}") String targetWebhookUrl) {
        
        this.restClient = restClientBuilder.build();
        this.targetWebhookUrl = targetWebhookUrl;
    }

    public void deliverEvent(Object taskEvent) {
        log.info("Sending task event via Webhook POST to {}: {}", targetWebhookUrl, taskEvent);

        try {
            String response = restClient.post()
                    .uri(targetWebhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(taskEvent)
                    .retrieve()
                    .body(String.class);

            log.info("Successfully delivered webhook event. Response: {}", response);
        } catch (Exception e) {
            log.error("Failed to deliver webhook event to {}", targetWebhookUrl, e);
        }
    }
}