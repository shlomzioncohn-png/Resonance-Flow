package com.rnd.gateway.controller;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.SocketTimeoutException;
import java.util.Map;

/**
 * Project Details Gateway Controller
 * Routes admin project detail requests to BFF
 */
@RestController
@RequestMapping("/api/admin/projects")
@Tag(name = "Admin Projects", description = "Project management for admins/managers")
public class ProjectDetailsController {
    
    private static final Logger logger = LoggerFactory.getLogger(ProjectDetailsController.class);
    
    private final RestTemplate restTemplate;
    
    @Value("${bff.service.url:http://localhost:8082}")
    private String bffServiceUrl;
    
    @Value("${bff.read-timeout-ms:10000}")
    private long bffReadTimeoutMs;
    
    public ProjectDetailsController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }
    
    @Operation(summary = "Get project details", description = "Get full project details for admin view")
    @GetMapping("/{projectId}")
    public ResponseEntity<Map<String, Object>> getProjectDetails(@PathVariable String projectId) {
        logger.info("Admin project details request - projectId: {}", projectId);
        
        try {
            String bffUrl = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                    .path("/admin/projects/{projectId}")
                    .buildAndExpand(projectId)
                    .toUriString();
            
            logger.debug("Forwarding to BFF: {}", bffUrl);
            
            HttpEntity<Void> getProjectRequest = new HttpEntity<>(null);
            
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> bffResponse = restTemplate.exchange(
                    bffUrl, HttpMethod.GET, getProjectRequest, Map.class);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> project = bffResponse.getBody();
            if (project == null) {
                throw new ServiceUnavailableException("bff", "BFF returned empty response");
            }
            
            logger.info("Received project details from BFF - projectId: {}", projectId);
            return ResponseEntity.ok(project);
            
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (GatewayTimeoutException e) {
            throw e;
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.error("BFF read timeout for project details - projectId: {}", projectId, e);
                throw new GatewayTimeoutException("bff", bffReadTimeoutMs, "BFF did not respond in time");
            }
            logger.error("BFF unreachable for project details - projectId: {}", projectId, e);
            throw new ServiceUnavailableException("bff", "BFF is currently unavailable");
            
        } catch (HttpStatusCodeException e) {
            logger.warn("BFF returned {} for project details - projectId: {}", e.getStatusCode(), projectId);
            throw new ResponseStatusException(e.getStatusCode(), "Project not found or access denied");
            
        } catch (Exception e) {
            logger.error("Unexpected error fetching project details for projectId: {}", projectId, e);
            throw new ServiceUnavailableException("bff", "Unexpected error communicating with BFF");
        }
    }
    
    @Operation(summary = "List all projects", description = "Get list of all projects for admin dashboard")
    @GetMapping
    public ResponseEntity<Map<String, Object>> listProjects() {
        logger.info("Admin projects list request");
        
        try {
            String bffUrl = UriComponentsBuilder.fromHttpUrl(bffServiceUrl)
                    .path("/admin/projects")
                    .toUriString();
            
            logger.debug("Forwarding to BFF: {}", bffUrl);
            
            HttpEntity<Void> getProjectsRequest = new HttpEntity<>(null);
            
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> bffResponse = restTemplate.exchange(
                    bffUrl, HttpMethod.GET, getProjectsRequest, Map.class);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> response = bffResponse.getBody();
            if (response == null) {
                throw new ServiceUnavailableException("bff", "BFF returned empty response");
            }
            
            logger.info("Received projects list from BFF");
            return ResponseEntity.ok(response);
            
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (GatewayTimeoutException e) {
            throw e;
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.error("BFF read timeout for projects list", e);
                throw new GatewayTimeoutException("bff", bffReadTimeoutMs, "BFF did not respond in time");
            }
            logger.error("BFF unreachable for projects list", e);
            throw new ServiceUnavailableException("bff", "BFF is currently unavailable");
            
        } catch (HttpStatusCodeException e) {
            logger.warn("BFF returned {} for projects list", e.getStatusCode());
            throw new ResponseStatusException(e.getStatusCode(), "Unable to fetch projects");
            
        } catch (Exception e) {
            logger.error("Unexpected error fetching projects list", e);
            throw new ServiceUnavailableException("bff", "Unexpected error communicating with BFF");
        }
    }
}
