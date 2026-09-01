package com.rnd.inbox.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI inboxOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Inbox Service API")
                .description("Team Inbox — canonical Task/User data model and REST APIs")
                .version("v1"));
    }
}
