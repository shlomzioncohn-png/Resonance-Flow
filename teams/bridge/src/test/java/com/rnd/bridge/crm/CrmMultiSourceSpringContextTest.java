package com.rnd.bridge.crm;

import com.rnd.bridge.crm.repository.DealRepository;
import com.rnd.bridge.crm.repository.JsonDealRepository;
import com.rnd.bridge.crm.repository.hubspot.HubSpotDealRepository;
import com.rnd.bridge.crm.repository.salesforce.SalesforceDealRepository;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.NoOpTaskEnricher;
import com.rnd.bridge.task.enrichment.TaskEnricher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Boots the real CRM beans ({@code CrmLaneConfig} + the three {@code DealRepository} impls,
 * registered directly rather than component-scanned to avoid sweeping up sibling test classes)
 * under each profile combination, with HubSpot's startup pipeline fetch served by a {@link
 * MockWebServer} - no live network call, no Kafka. Confirms {@link CrmSyncService} gets the right
 * beans once {@code @Primary} no longer arbitrates between them (Step 1).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CrmMultiSourceSpringContextTest {

    private static final String DEFAULT_PIPELINE = """
            {
              "results": [
                {
                  "id": "pl-1",
                  "label": "Sales Pipeline",
                  "stages": [
                    {"id": "st-100", "label": "Appointment Scheduled", "metadata": {"isClosed": "false", "probability": "0.2"}},
                    {"id": "st-500", "label": "Closed Won", "metadata": {"isClosed": "true", "probability": "1.0"}},
                    {"id": "st-600", "label": "Closed Lost", "metadata": {"isClosed": "true", "probability": "0.0"}}
                  ]
                }
              ]
            }
            """;

    private MockWebServer hubspotServer;

    @BeforeEach
    void startServer() throws IOException {
        hubspotServer = new MockWebServer();
        hubspotServer.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        hubspotServer.shutdown();
    }

    private void enqueuePipeline() {
        hubspotServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(DEFAULT_PIPELINE));
    }

    @Configuration
    static class TestCollaborators {
        @Bean
        EnrichmentProperties enrichmentProperties() {
            return new EnrichmentProperties();
        }

        @Bean
        TaskEnricher taskEnricher() {
            return new NoOpTaskEnricher();
        }

        // Prototype-scoped: HubSpotDealRepository/SalesforceDealRepository each mutate their own
        // builder (baseUrl, requestFactory) - sharing one singleton instance between them would
        // make one repository's configuration bleed into the other's.
        @Bean
        @Scope("prototype")
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        CrmLaneConfig.class,
                        CrmLane.class,
                        CrmSourceReader.class,
                        CrmSyncService.class,
                        JsonDealRepository.class,
                        HubSpotDealRepository.class,
                        SalesforceDealRepository.class,
                        TestCollaborators.class)
                .withPropertyValues(
                        "bridge.crm.hubspot.token=test-token",
                        "bridge.crm.hubspot.base-url=" + hubspotServer.url("/"),
                        "bridge.crm.hubspot.max-retries=1",
                        "bridge.crm.salesforce.login-url=https://example.my.salesforce.com",
                        "bridge.crm.salesforce.client-id=test-client-id",
                        "bridge.crm.salesforce.client-secret=test-client-secret");
    }

    @Test
    void bothProfilesActiveStartsAndInjectsTwoRepositoriesIntoCrmSyncService() {
        enqueuePipeline();

        contextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().addActiveProfile("hubspot");
                    context.getEnvironment().addActiveProfile("salesforce");
                })
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    Map<String, DealRepository> repositories = context.getBeansOfType(DealRepository.class);
                    assertEquals(2, repositories.size());
                    Set<String> sources = repositories.values().stream()
                            .map(DealRepository::source)
                            .collect(Collectors.toSet());
                    assertEquals(Set.of("hubspot", "salesforce"), sources);

                    CrmSyncService service = context.getBean(CrmSyncService.class);
                    @SuppressWarnings("unchecked")
                    List<DealRepository> injected =
                            (List<DealRepository>) ReflectionTestUtils.getField(service, "dealRepositories");
                    assertEquals(2, injected.size(), "CrmSyncService must receive both active repositories");
                });
    }

    @Test
    void noCrmProfileActiveOnlyJsonDealRepositoryIsRegistered() {
        contextRunner().run(context -> {
            assertNull(context.getStartupFailure());
            Map<String, DealRepository> repositories = context.getBeansOfType(DealRepository.class);
            assertEquals(1, repositories.size());
            assertEquals("json", repositories.values().iterator().next().source());
        });
    }
}
