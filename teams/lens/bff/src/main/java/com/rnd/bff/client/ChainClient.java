package com.rnd.bff.client;

import com.rnd.bff.dto.TaskDependenciesDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Client for Chain — owns dependency/blocker data and feasibility (see
 * docs/lens-architecture-design.md).
 *
 * - Feasibility: Chain's API for it isn't live yet, so getFeasibility returns
 *   a fixed mock (no dependencies, no constraints) for every task id.
 * - Task history (the employee task page's Activity tab): real calls to
 *   GET /tasks/{taskId}/state-history and GET /tasks/{taskId}/dependencies/history.
 *   Every call carries X-Tenant-Id - Chain answers 400 without a valid one,
 *   and 404 for a task that isn't in that tenant (or doesn't exist).
 * - Dependency graph (the manager's Bottlenecks screen): real call to
 *   GET /tenants/{tenantId}/dependency-graph.
 */
@Service
public class ChainClient {

    static final String TENANT_HEADER = "X-Tenant-Id";
    /** The task spec's page size for both histories; Chain allows up to 100. */
    static final int HISTORY_PAGE_SIZE = 50;
    /** Chain's own default for the dependency graph. */
    static final int GRAPH_LIMIT = 500;

    private final RestTemplate restTemplate;

    @Value("${chain.service.url:http://chain-service:8081}")
    private String chainServiceUrl;

    public ChainClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // TODO: replace with GET {chainServiceUrl}/api/v1/dependencies?taskIds=... once Chain exposes it
    public Map<String, TaskDependenciesDTO> getFeasibility(String userId, List<String> taskIds) {
        return taskIds.stream()
                .collect(Collectors.toMap(id -> id, id -> new TaskDependenciesDTO(List.of(), List.of())));
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
