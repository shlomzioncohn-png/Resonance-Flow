package com.rnd.gateway.controller;

import com.rnd.gateway.dto.ManagerProjectDTO;
import com.rnd.gateway.dto.WorkloadEntryDTO;
import com.rnd.gateway.exception.GatewayTimeoutException;
import com.rnd.gateway.exception.ServiceUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.SocketTimeoutException;
import java.util.List;

/**
 * Manager Dashboard Gateway Controller
 * Routes team-workload and all-projects requests to BFF.
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

    @Operation(summary = "Get team workload", description = "Active-task count per employee.")
    @GetMapping("/workload")
    public ResponseEntity<List<WorkloadEntryDTO>> getWorkload() {
        logger.info("Manager workload request");

        try {
            String bffUrl = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                    .path("/manager/workload")
                    .toUriString();
            logger.debug("Forwarding to BFF: {}", bffUrl);

            ResponseEntity<WorkloadEntryDTO[]> bffResponse = restTemplate.exchange(
                    bffUrl, HttpMethod.GET, new HttpEntity<>(null), WorkloadEntryDTO[].class);

            WorkloadEntryDTO[] workload = bffResponse.getBody();
            if (workload == null) {
                throw new ServiceUnavailableException("bff", "BFF returned empty response");
            }

            logger.info("Received workload from BFF - count: {}", workload.length);
            return ResponseEntity.ok(List.of(workload));

        } catch (ServiceUnavailableException | GatewayTimeoutException | ResponseStatusException e) {
            throw e;
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.error("BFF read timeout for workload", e);
                throw new GatewayTimeoutException("bff", bffReadTimeoutMs, "BFF did not respond in time");
            }
            logger.error("BFF unreachable for workload", e);
            throw new ServiceUnavailableException("bff", "BFF is currently unavailable");

        } catch (HttpStatusCodeException e) {
            logger.warn("BFF returned {} for workload", e.getStatusCode());
            throw new ResponseStatusException(e.getStatusCode(), "Downstream service returned an error");

        } catch (Exception e) {
            logger.error("Unexpected error fetching workload", e);
            throw new ServiceUnavailableException("bff", "Unexpected error communicating with BFF");
        }
    }

    @Operation(summary = "List all projects", description = "Project grid with health/KPI data for the Manager Dashboard.")
    @GetMapping("/projects")
    public ResponseEntity<List<ManagerProjectDTO>> getProjects() {
        logger.info("Manager projects request");

        try {
            String bffUrl = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                    .path("/manager/projects")
                    .toUriString();
            logger.debug("Forwarding to BFF: {}", bffUrl);

            ResponseEntity<ManagerProjectDTO[]> bffResponse = restTemplate.exchange(
                    bffUrl, HttpMethod.GET, new HttpEntity<>(null), ManagerProjectDTO[].class);

            ManagerProjectDTO[] projects = bffResponse.getBody();
            if (projects == null) {
                throw new ServiceUnavailableException("bff", "BFF returned empty response");
            }

            logger.info("Received projects from BFF - count: {}", projects.length);
            return ResponseEntity.ok(List.of(projects));

        } catch (ServiceUnavailableException | GatewayTimeoutException | ResponseStatusException e) {
            throw e;
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.error("BFF read timeout for projects", e);
                throw new GatewayTimeoutException("bff", bffReadTimeoutMs, "BFF did not respond in time");
            }
            logger.error("BFF unreachable for projects", e);
            throw new ServiceUnavailableException("bff", "BFF is currently unavailable");

        } catch (HttpStatusCodeException e) {
            logger.warn("BFF returned {} for projects", e.getStatusCode());
            throw new ResponseStatusException(e.getStatusCode(), "Downstream service returned an error");

        } catch (Exception e) {
            logger.error("Unexpected error fetching projects", e);
            throw new ServiceUnavailableException("bff", "Unexpected error communicating with BFF");
        }
    }
}
