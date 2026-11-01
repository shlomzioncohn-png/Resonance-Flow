package com.rnd.bff.client;

import com.rnd.bff.dto.ChainFlowHealthFlagDTO;
import com.rnd.bff.dto.ChainFlowHealthPageDTO;
import com.rnd.bff.dto.TaskDependenciesDTO;
import com.rnd.bff.exception.ServiceUnavailableException;
import com.rnd.bff.dto.ConstraintDTO;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client for Chain — owns dependency/blocker data and feasibility (see
 * docs/lens-architecture-design.md).
 *
 * - Feasibility: getFeasibility fetches each task's constraints from
 *   GET /tasks/{taskId}/constraints. Dependencies are still always empty -
 *   Chain has no batch feasibility API yet.
 * - Task history (the employee task page's Activity tab): real calls to
 *   GET /tasks/{taskId}/state-history and GET /tasks/{taskId}/dependencies/history.
 *   Every call carries X-Tenant-Id - Chain answers 400 without a valid one,
 *   and 404 for a task that isn't in that tenant (or doesn't exist).
 * - Dependency graph (the manager's Bottlenecks screen): real call to
 *   GET /tenants/{tenantId}/dependency-graph.
 * - Flow-health flags (the manager's Flow health screen): real, read-only call to
 *   GET /flow-health/flags.
 */
@Service
public class ChainClient {

    private static final Logger logger = LoggerFactory.getLogger(ChainClient.class);

    /** The only header Chain reads - it has no notion of an acting user. */
    static final String TENANT_HEADER = "X-Tenant-Id";
    /** The task spec's page size for both histories; Chain allows up to 100. */
    static final int HISTORY_PAGE_SIZE = 50;
    /** Chain's own default for the dependency graph. */
    static final int GRAPH_LIMIT = 500;
    private static final String FLOW_HEALTH_FLAGS_PATH = "/flow-health/flags";
    private static final int FLAGS_PAGE_SIZE = 100;
    /** A safety stop for getFlowHealthFlags: 20 pages of 100 = 2,000 flags. */
    private static final int FLAGS_MAX_PAGES = 20;

    /** How many constraint calls to Chain may be in flight at once for one BFF instance. */
    static final int CONSTRAINT_CALLS_IN_PARALLEL = 8;

    private final RestTemplate restTemplate;

    private final ExecutorService constraintCalls = Executors.newFixedThreadPool(CONSTRAINT_CALLS_IN_PARALLEL, task -> {
        Thread thread = new Thread(task, "chain-constraints");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${chain.service.url:http://chain-service:8081}")
    private String chainServiceUrl;

    public ChainClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Constraints for each task, from GET /tasks/{taskId}/constraints - one call
     * per task. Every task id gets an entry: a task whose call fails (Chain
     * down, 400, 404, ...) gets an empty list and a warning in the log, so one
     * bad task never fails the whole screen.
     *
     * Dependencies stay empty for now - Chain has no batch feasibility API yet.
     *
     * @param tenantId the signed-in user's tenant (from Inbox, never from the frontend)
     */
    public Map<String, TaskDependenciesDTO> getFeasibility(String tenantId, List<String> taskIds) {
        Map<String, TaskDependenciesDTO> result = new LinkedHashMap<>();
        if (tenantId == null || tenantId.isBlank()) {
            // Chain answers 400 to every call without a tenant - don't make them.
            logger.warn("No tenant known - showing no Chain constraints for {} task(s)", taskIds.size());
            taskIds.forEach(id -> result.put(id, new TaskDependenciesDTO(List.of(), List.of())));
            return result;
        }
        // A page of My Tasks is up to 100 tasks: the calls run side by side
        // (at most CONSTRAINT_CALLS_IN_PARALLEL at once), not one after another.
        Map<String, CompletableFuture<List<ConstraintDTO>>> calls = new LinkedHashMap<>();
        for (String taskId : taskIds) {
            calls.computeIfAbsent(taskId, id -> CompletableFuture.supplyAsync(
                    () -> getConstraintsOrEmpty(tenantId, id), constraintCalls));
        }
        // getConstraintsOrEmpty never throws, so join() doesn't either.
        calls.forEach((id, call) -> result.put(id, new TaskDependenciesDTO(List.of(), call.join())));
        return result;
    }

    @PreDestroy
    void shutdown() {
        constraintCalls.shutdownNow();
    }

    private List<ConstraintDTO> getConstraintsOrEmpty(String tenantId, String taskId) {
        URI uri = UriComponentsBuilder.fromHttpUrl(chainServiceUrl)
                .path("/tasks/{taskId}/constraints")
                .buildAndExpand(taskId)
                .encode()
                .toUri();
        try {
            List<ConstraintDTO> constraints = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(tenantHeaders(tenantId)),
                    new ParameterizedTypeReference<List<ConstraintDTO>>() { })
                    .getBody();
            return constraints == null ? List.of() : constraints;
        } catch (RuntimeException ex) {
            logger.warn("Chain constraints unavailable for task {} - showing none: {}", taskId, ex.getMessage());
            return List.of();
        }
    }

    /** X-Tenant-Id only - Chain requires it and reads no other identity header. */
    private static HttpHeaders tenantHeaders(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TENANT_HEADER, tenantId);
        return headers;
    }

    /** Status and blocking changes (page 0, 50 rows). A task Chain doesn't have (404) has no history. */
    public List<ChainStateHistoryEntry> getStateHistory(String taskId, String tenantId) {
        return getHistory(taskId, tenantId, "/tasks/{taskId}/state-history",
                new ParameterizedTypeReference<ChainPage<ChainStateHistoryEntry>>() { });
    }

    /** Dependencies added/removed (page 0, 50 rows). A task Chain doesn't have (404) has no history. */
    public List<ChainDependencyHistoryEntry> getDependencyHistory(String taskId, String tenantId) {
        return getHistory(taskId, tenantId, "/tasks/{taskId}/dependencies/history",
                new ParameterizedTypeReference<ChainPage<ChainDependencyHistoryEntry>>() { });
    }

    /**
     * The tenant's dependency graph, at most GRAPH_LIMIT tasks (Chain keeps the ones that
     * block the most). Chain takes the tenant from the path; the header is sent anyway, like
     * every other Chain call.
     */
    public ChainDependencyGraph getDependencyGraph(String tenantId) {
        URI uri = UriComponentsBuilder.fromHttpUrl(chainServiceUrl)
                .path("/tenants/{tenantId}/dependency-graph")
                .queryParam("limit", GRAPH_LIMIT)
                .buildAndExpand(tenantId)
                .encode()
                .toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.set(TENANT_HEADER, tenantId);
        ChainDependencyGraph graph = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(headers), ChainDependencyGraph.class).getBody();
        return graph == null ? new ChainDependencyGraph(List.of(), List.of(), false) : graph;
    }

    /**
     * Every flow-health flag Chain holds for {@code tenantId} (all pages), ordered by rank. Read-only.
     * Chain scopes the flags by the {@code X-Tenant-Id} header and answers 400 without a valid one.
     *
     * @throws ServiceUnavailableException if Chain is unreachable or answers with an error or an empty body;
     *                                     the cause is logged, never sent to the caller
     */
    public List<ChainFlowHealthFlagDTO> getFlowHealthFlags(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TENANT_HEADER, tenantId);
        List<ChainFlowHealthFlagDTO> flags = new ArrayList<>();
        try {
            int page = 0;
            ChainFlowHealthPageDTO current;
            do {
                URI uri = UriComponentsBuilder.fromHttpUrl(chainServiceUrl)
                        .path(FLOW_HEALTH_FLAGS_PATH)
                        .queryParam("page", page)
                        .queryParam("size", FLAGS_PAGE_SIZE)
                        .build()
                        .toUri();
                current = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers),
                        ChainFlowHealthPageDTO.class).getBody();
                if (current == null || current.getContent() == null) {
                    throw new ServiceUnavailableException("chain", "Chain returned an empty response");
                }
                flags.addAll(current.getContent());
                page++;
            } while (page < current.getTotalPages() && page < FLAGS_MAX_PAGES);
        } catch (RestClientException e) {
            logger.error("Chain flow-health request failed", e);
            throw new ServiceUnavailableException("chain", "Chain is currently unavailable", e);
        }
        return flags;
    }

    private <T> List<T> getHistory(String taskId, String tenantId, String path,
                                   ParameterizedTypeReference<ChainPage<T>> type) {
        URI uri = UriComponentsBuilder.fromHttpUrl(chainServiceUrl)
                .path(path)
                .queryParam("page", 0)
                .queryParam("size", HISTORY_PAGE_SIZE)
                .buildAndExpand(taskId)
                .encode()
                .toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.set(TENANT_HEADER, tenantId);
        try {
            ChainPage<T> page = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), type).getBody();
            return page == null || page.content() == null ? List.of() : page.content();
        } catch (HttpClientErrorException.NotFound ex) {
            // Chain doesn't tell "not in your tenant" from "doesn't exist" - either way, nothing to show.
            return List.of();
        }
    }
}
