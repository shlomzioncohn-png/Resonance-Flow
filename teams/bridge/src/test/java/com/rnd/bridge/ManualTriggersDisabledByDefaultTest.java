package com.rnd.bridge;

import com.rnd.bridge.crm.controller.CrmBridgeController;
import com.rnd.bridge.github.controller.GitHubBridgeController;
import com.rnd.bridge.intake.controller.UnstructuredIntakeController;
import com.rnd.bridge.jira.controller.JiraBridgeController;
import com.rnd.bridge.media.controller.MediaIntakeController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The manual sync/run endpoints have no auth, so without bridge.manual-triggers.enabled=true
 * their controllers aren't registered at all and every one of them is a 404.
 */
@WebMvcTest(controllers = {
        CrmBridgeController.class, GitHubBridgeController.class, JiraBridgeController.class,
        UnstructuredIntakeController.class, MediaIntakeController.class})
class ManualTriggersDisabledByDefaultTest {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {
            "/bridge/crm/sync",
            "/api/bridge/github/sync",
            "/api/bridge/jira/sync",
            "/api/bridge/intake/EMAIL/run",
            "/api/bridge/media/run"})
    void manualEndpointIsNotMappedUnlessEnabled(String path) throws Exception {
        mockMvc.perform(post(path)).andExpect(status().isNotFound());
    }
}
