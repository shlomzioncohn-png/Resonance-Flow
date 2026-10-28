package com.rnd.bff.client;

import com.rnd.bff.dto.ConstraintDTO;
import com.rnd.bff.dto.TaskDependenciesDTO;
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
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
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
 */
@Service
public class ChainClient {

    private static final Logger logger = LoggerFactory.getLogger(ChainClient.class);

    /** The only header Chain reads - it has no notion of an acting user. */
    static final String TENANT_HEADER = "X-Tenant-Id";
    /** The task spec's page size for both histories; Chain allows up to 100. */
    static final int HISTORY_PAGE_SIZE = 50;

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
