package com.rnd.inbox.shared.config;

import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.shared.dto.ErrorResponseDto;
import com.rnd.inbox.shared.security.ActorHeaderExtractor;
import com.rnd.inbox.shared.security.CurrentActor;
import com.rnd.inbox.shared.security.RequireRole;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;

import java.util.Arrays;

@Configuration
public class OpenApiConfig {

    private static final String ERROR_SCHEMA_REF = "#/components/schemas/ErrorResponseDto";

    // A @CurrentActor parameter is filled from the X-Actor-Id header by CurrentActorArgumentResolver,
    // which springdoc can't see - left alone it documents it as an "actorId" query parameter the
    // server never reads. The header itself is added back by actorHeaderCustomizer().
    static {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentActor.class);
    }

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

    @Bean
    public OperationCustomizer actorHeaderCustomizer() {
        return (operation, handlerMethod) -> {
            if (readsActorHeader(handlerMethod)) {
                operation.addParametersItem(new HeaderParameter()
                        .name(ActorHeaderExtractor.ACTOR_HEADER)
                        .description("Id of the user performing the request")
                        .required(true)
                        .schema(new StringSchema().format("uuid")));
            }
            return operation;
        };
    }

    // Same two places that read the header at runtime: CurrentActorArgumentResolver for a
    // @CurrentActor parameter, RoleAuthorizationInterceptor for a @RequireRole method.
    private boolean readsActorHeader(HandlerMethod handlerMethod) {
        return handlerMethod.hasMethodAnnotation(RequireRole.class)
                || Arrays.stream(handlerMethod.getMethodParameters())
                        .anyMatch(parameter -> parameter.hasParameterAnnotation(CurrentActor.class));
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
