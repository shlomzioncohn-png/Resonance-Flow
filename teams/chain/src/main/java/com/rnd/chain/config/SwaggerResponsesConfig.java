
package com.rnd.chain.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerResponsesConfig {

    @Bean
    public OpenApiCustomizer chainApiResponses() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }

            openApi.getPaths().forEach((path, pathItem) ->
                    pathItem.readOperationsMap().forEach((method, operation) ->
                            addResponses(path, method, operation)
                    )
            );
        };
    }

    private void addResponses(
            String path,
            PathItem.HttpMethod method,
            Operation operation) {

        if (!path.startsWith("/tasks/")) {
            return;
        }

        switch (path) {

            case "/tasks/{taskId}/eligibility":
                if (method == PathItem.HttpMethod.GET) {
                    response(operation, "200",
                            "Eligibility returned successfully");
                    response(operation, "404",
                            "Task not found");
                }
                break;

            case "/tasks/{taskId}/dependencies":
                if (method == PathItem.HttpMethod.GET) {
                    response(operation, "200",
                            "Dependencies returned successfully");
                    response(operation, "404",
                            "Task not found");
                } else if (method == PathItem.HttpMethod.POST) {
                    created(operation,
                            "Dependency created successfully");
                    response(operation, "400",
                            "Invalid dependency request");
                    response(operation, "404",
                            "Task not found");
                    response(operation, "409",
                            "Dependency already exists");
                }
                break;

            case "/tasks/{taskId}/dependencies/{dependsOnTaskId}":
                if (method == PathItem.HttpMethod.DELETE) {
                    noContent(operation,
                            "Dependency removed successfully");
                    response(operation, "404",
                            "Dependency not found");
                }
                break;

            case "/tasks/{taskId}/dependents":
                if (method == PathItem.HttpMethod.GET) {
                    response(operation, "200",
                            "Dependents returned successfully");
                    response(operation, "404",
                            "Task not found");
                }
                break;

            case "/tasks/{taskId}/blockers":
                if (method == PathItem.HttpMethod.GET) {
                    response(operation, "200",
                            "Blockers returned successfully");
                    response(operation, "404",
                            "Task not found");
                }
                break;

            case "/tasks/{taskId}/ancestors":
                if (method == PathItem.HttpMethod.GET) {
                    response(operation, "200",
                            "Ancestors returned successfully");
                    response(operation, "404",
                            "Task not found");
                }
                break;

            case "/tasks/{taskId}/descendants":
                if (method == PathItem.HttpMethod.GET) {
                    response(operation, "200",
                            "Descendants returned successfully");
                    response(operation, "404",
                            "Task not found");
                }
                break;

            case "/tasks/{taskId}/constraints":
                if (method == PathItem.HttpMethod.GET) {
                    response(operation, "200",
                            "Constraints returned successfully");
                    response(operation, "404",
                            "Task not found");
                } else if (method == PathItem.HttpMethod.POST) {
                    created(operation,
                            "Constraint created successfully");
                    response(operation, "400",
                            "Invalid constraint request");
                    response(operation, "404",
                            "Task not found");
                }
                break;

            case "/tasks/{taskId}/constraints/{constraintId}":
                if (method == PathItem.HttpMethod.PATCH) {
                    response(operation, "200",
                            "Constraint updated successfully");
                    response(operation, "400",
                            "Invalid status request");
                    response(operation, "404",
                            "Constraint not found");
                }
                break;

            default:
                // Do not modify documentation of unrelated endpoints.
                break;
        }
    }

    private void response(
            Operation operation,
            String status,
            String description) {

        ApiResponses responses = getResponses(operation);

        ApiResponse apiResponse = responses.get(status);

        if (apiResponse == null) {
            apiResponse = new ApiResponse();
            responses.addApiResponse(status, apiResponse);
        }

        // Keep any existing response schema/content.
        apiResponse.setDescription(description);
    }

    private void created(Operation operation, String description) {
        ApiResponses responses = getResponses(operation);

        // Springdoc may infer 200 for a method that actually returns 201.
        // Reuse its schema/content under the correct status.
        ApiResponse success = responses.remove("200");

        if (success == null) {
            success = responses.get("201");
        }

        if (success == null) {
            success = new ApiResponse();
        }

        success.setDescription(description);
        responses.addApiResponse("201", success);
    }

    private void noContent(Operation operation, String description) {
        ApiResponses responses = getResponses(operation);

        responses.remove("200");
        responses.addApiResponse(
                "204",
                new ApiResponse().description(description)
        );
    }

    private ApiResponses getResponses(Operation operation) {
        ApiResponses responses = operation.getResponses();

        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }

        return responses;
    }
}