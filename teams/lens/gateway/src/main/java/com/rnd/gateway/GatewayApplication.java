package com.rnd.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
    
    /**
     * RestTemplate bean for making HTTP calls to BFF and other services
     * Used in HealthController to check BFF availability
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
