package com.rnd.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI at /swagger-ui.html, raw OpenAPI spec at /v3/api-docs.
 * SecurityConfig already permitAll()s every request, so both are reachable
 * without auth — same as every other endpoint here today.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI gatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LENS Gateway API")
                        .description(
                                "Single entry point for the Frontend. Routes /api/workspace/** to the BFF — "
                                        + "see GATEWAY_CONTRACT.md for the full contract and error format.")
                        .version("0.1.0"));
    }
}
