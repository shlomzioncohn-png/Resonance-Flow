package com.rnd.inbox.shared.config;

import com.rnd.inbox.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// OpenApiConfigTest covers the header customizer on its own. Hiding the @CurrentActor parameter
// happens inside springdoc's own scan of the controllers, so only the generated document - the
// same /v3/api-docs the Swagger UI reads - can prove it. Read-only: creates no rows.
@AutoConfigureMockMvc
class OpenApiConfigIntegrationTest extends PostgresIntegrationTest {

    private static final String PROJECTS = "$.paths['/api/v1/projects']";
    private static final String PROJECT_BY_ID = "$.paths['/api/v1/projects/{id}']";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocs_currentActorEndpoint_hasNoActorIdQueryParameter() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PROJECTS + ".get.parameters[?(@.name == 'actorId')]").isEmpty());
    }

    @Test
    void apiDocs_currentActorEndpoint_documentsRequiredActorHeader() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(PROJECTS + ".get.parameters"
                        + "[?(@.name == 'X-Actor-Id' && @.in == 'header' && @.required == true)]", hasSize(1)));
    }

    @Test
    void apiDocs_endpointWithRequireRoleAndCurrentActor_documentsActorHeaderOnce() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(PROJECTS + ".post.parameters[?(@.name == 'X-Actor-Id')]", hasSize(1)));
    }

    @Test
    void apiDocs_endpointWithPathVariable_keepsPathVariableAlongsideActorHeader() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(PROJECT_BY_ID + ".patch.parameters[?(@.name == 'id' && @.in == 'path')]", hasSize(1)))
                .andExpect(jsonPath(PROJECT_BY_ID + ".patch.parameters[?(@.name == 'X-Actor-Id')]", hasSize(1)))
                .andExpect(jsonPath(PROJECT_BY_ID + ".patch.parameters[?(@.name == 'actorId')]").isEmpty());
    }
}
