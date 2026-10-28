package com.rnd.gateway.controller;

import com.rnd.gateway.dto.DependencyGraphDTO;
import com.rnd.gateway.dto.ManagerProjectDTO;
import com.rnd.gateway.exception.GatewayTimeoutException;
import com.rnd.gateway.exception.ServiceUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.List;

/**
 * Manager Dashboard Gateway Controller
 * Routes all-projects and Bottlenecks dependency-graph requests to BFF.
 */
@RestController
@RequestMapping("/api/manager")
@Tag(name = "Manager", description = "Manager Dashboard screens — forwards to BFF")
public class ManagerController {

    private static final Logger logger = LoggerFactory.getLogger(ManagerController.class);

    private final RestTemplate restTemplate;

    @Value("${bff.service.url:http://localhost:8082}")
    private String bffServiceUrl;

    @Value("${bff.read-timeout-ms:10000}")
    private long bffReadTimeoutMs;

    public ManagerController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Operation(summary = "List all projects", description = "Project grid with health/KPI data for the Manager Dashboard.")
    @GetMapping("/projects")
    public ResponseEntity<List<ManagerProjectDTO>> getProjects() {
        logger.info("Manager projects request");

        URI bffUrl = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                .path("/manager/projects")
                .build()
                .toUri();
        ManagerProjectDTO[] projects = forward(bffUrl, ManagerProjectDTO[].class, "projects");

        logger.info("Received projects from BFF - count: {}", projects.length);
        return ResponseEntity.ok(List.of(projects));
    }

    @Operation(summary = "Task dependency graph",
            description = "The Bottlenecks screen: Chain's dependency graph for the manager's tenant, with task titles. "
                    + "Optional ?projectId= keeps only that project's tasks.")
    @GetMapping("/bottlenecks/graph")
    public ResponseEntity<DependencyGraphDTO> getDependencyGraph(@RequestParam(required = false) String projectId) {
        logger.info("Bottlenecks graph request - projectId: {}", projectId);

        UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(bffServiceUrl).path("/manager/bottlenecks/graph");
        if (projectId != null && !projectId.isBlank()) {
            uri.queryParam("projectId", projectId);
        }
        DependencyGraphDTO graph = forward(uri.encode().build().toUri(), DependencyGraphDTO.class, "dependency graph");

        logger.info("Received dependency graph from BFF - nodes: {}", graph.nodes() == null ? 0 : graph.nodes().size());
        return ResponseEntity.ok(graph);
    }

    /** GET from BFF as the acting manager, with the same error mapping for every manager screen. */
    private <T> T forward(URI bffUrl, Class<T> type, String what) {
        try {
            logger.debug("Forwarding to BFF: {}", bffUrl);

            // BFF reads the user's tenant and tasks from Inbox. Mock identity, same as
            // ManagerDashboardController (TODO Auth stage 2).
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-User-Id", "mgr_001");
            headers.set("X-User-Role", "MANAGER");
            T body = restTemplate.exchange(bffUrl, HttpMethod.GET, new HttpEntity<>(headers), type).getBody();
            if (body == null) {
                throw new ServiceUnavailableException("bff", "BFF returned empty response");
            }
            return body;

        } catch (ServiceUnavailableException | GatewayTimeoutException | ResponseStatusException e) {
            throw e;
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.error("BFF read timeout for {}", what, e);
                throw new GatewayTimeoutException("bff", bffReadTimeoutMs, "BFF did not respond in time");
            }
            logger.error("BFF unreachable for {}", what, e);
            throw new ServiceUnavailableException("bff", "BFF is currently unavailable");

        } catch (HttpStatusCodeException e) {
            logger.warn("BFF returned {} for {}", e.getStatusCode(), what);
            throw new ResponseStatusException(e.getStatusCode(), "Downstream service returned an error");

        } catch (Exception e) {
            logger.error("Unexpected error fetching {}", what, e);
            throw new ServiceUnavailableException("bff", "Unexpected error communicating with BFF");
        }
    }
}
