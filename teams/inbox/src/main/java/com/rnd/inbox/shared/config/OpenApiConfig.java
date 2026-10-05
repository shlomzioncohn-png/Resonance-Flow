package com.rnd.inbox.shared.config;

import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.shared.dto.ErrorResponseDto;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String ERROR_SCHEMA_REF = "#/components/schemas/ErrorResponseDto";

    @Bean
    public OpenAPI inboxOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Inbox Service API")
                .description("Team Inbox — canonical Task/User data model and REST APIs")
                .version("v1"));
    }

    @Bean
    public OperationCustomizer errorResponsesCustomizer() {
        return (operation, handlerMethod) -> {
            addIfMissing(operation, "400", "Request validation failed");
            addIfMissing(operation, "500", "An unexpected error occurred");
            fillMissingErrorSchemas(operation);
            return operation;
        };
    }

    private void addIfMissing(Operation operation, String code, String description) {
        if (operation.getResponses().containsKey(code)) {
            return;
        }
        operation.getResponses().addApiResponse(code, new ApiResponse()
                .description(description)
                .content(errorContent()));
    }

    private void fillMissingErrorSchemas(Operation operation) {
        operation.getResponses().forEach((code, response) -> {
            boolean hasExplicitJsonContent = response.getContent() != null
                    && response.getContent().containsKey("application/json");
            if (!code.startsWith("2") && !hasExplicitJsonContent) {
                response.setContent(errorContent());
            }
        });
    }

    private Content errorContent() {
        Schema<ErrorResponseDto> schema = new Schema<>();
        schema.set$ref(ERROR_SCHEMA_REF);
        return new Content().addMediaType("application/json", new MediaType().schema(schema));
    }
}
