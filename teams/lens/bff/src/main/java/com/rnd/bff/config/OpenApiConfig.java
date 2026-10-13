package com.rnd.bff.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI at /swagger-ui.html, raw OpenAPI spec at /v3/api-docs.
 * BFF has no spring-security dependency, so nothing blocks these by default.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bffOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LENS BFF API")
                        .description(
                                "Called only by the Gateway (never directly by the Frontend) — "
                                        + "see docs/workspace-screen-integration-plan.md for the full contract.")
                        .version("0.1.0"));
    }
}
