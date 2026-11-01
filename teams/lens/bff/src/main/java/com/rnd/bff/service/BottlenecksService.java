package com.rnd.bff.service;

import com.rnd.bff.client.ChainClient;
import com.rnd.bff.client.ChainDependencyGraph;
import com.rnd.bff.client.InboxClient;
import com.rnd.bff.dto.DependencyGraphDTO;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.exception.InboxRejectedException;
import com.rnd.bff.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The manager's Bottlenecks screen: Chain's dependency graph for the signed-in
 * user's tenant, with each task's title from Inbox.
 *
 * - The tenant comes from the user's Inbox record (UserTenantResolver), never
 *   from the frontend.
 * - Chain has no titles and no projects, so both come from the tenant's Inbox
 *   tasks. A task Chain has but Inbox doesn't is "Unknown task", and is left
 *   out when filtering by project.
 * - blocksCount is Chain's count over the whole tenant, so with a project
 *   filter it can count tasks of other projects too - they're still waiting.
 * - isBlocked is Chain's "not ready": an unfinished dependency or an open
 *   (PENDING) constraint. Marking the last constraint satisfied or waived
 *   clears it, once nothing else blocks the task.
 * - Chain doesn't send an edge's confidence yet: an edge without one is 1.0,
 *   the same default Chain itself uses for edges that didn't come from Mind.
 */
@Service
public class BottlenecksService {

    private static final Logger logger = LoggerFactory.getLogger(BottlenecksService.class);

    static final String UNKNOWN_TASK_TITLE = "Unknown task";
    static final double DEFAULT_CONFIDENCE = 1.0;

    private final ChainClient chainClient;
    private final InboxClient inboxClient;
    private final UserTenantResolver tenantResolver;

    public BottlenecksService(ChainClient chainClient, InboxClient inboxClient, UserTenantResolver tenantResolver) {
        this.chainClient = chainClient;
        this.inboxClient = inboxClient;
        this.tenantResolver = tenantResolver;
    }

    /**
     * @param userId    the acting manager (X-User-Id)
     * @param projectId an Inbox project id, or null for the whole tenant
     */
    public DependencyGraphDTO getGraph(String userId, String projectId) {
        String tenantId = tenantResolver.tenantOf(userId);

        ChainDependencyGraph graph;
        try {
            graph = chainClient.getDependencyGraph(tenantId);
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("chain", "The dependency graph is unavailable", ex);
        }
        List<TaskDTO> tasks;
        try {
            tasks = inboxClient.getTenantTasks(userId);
        } catch (InboxRejectedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("inbox", "Inbox is unavailable", ex);
        }

        DependencyGraphDTO result = toGraph(graph, tasks, projectId);
        if (graph.hasMore()) {
            logger.warn("Tenant {} has more than {} tasks in Chain - the graph shows the top ones only",
                    tenantId, result.nodes().size());
        }
        logger.info("Bottlenecks graph - project: {}, nodes: {}, edges: {}",
                projectId, result.nodes().size(), result.edges().size());
        return result;
    }

    /** The mapping itself, separate from fetching so it can be tested on its own. */
    static DependencyGraphDTO toGraph(ChainDependencyGraph graph, List<TaskDTO> tasks, String projectId) {
        if (projectId != null && projectId.isBlank()) {
            projectId = null;
        }
        Map<String, TaskDTO> tasksById = new HashMap<>();
        for (TaskDTO task : tasks) {
            if (task.getId() != null) {
                tasksById.put(task.getId(), task);
            }
        }

        List<DependencyGraphDTO.Node> nodes = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        for (ChainDependencyGraph.Node node : graph.nodes() == null ? List.<ChainDependencyGraph.Node>of() : graph.nodes()) {
            TaskDTO task = tasksById.get(node.taskId());
            if (projectId != null && (task == null || !projectId.equals(task.getProjectId()))) {
                continue;
            }
            String title = task != null && task.getTitle() != null ? task.getTitle() : UNKNOWN_TASK_TITLE;
            // Chain's isReady counts both unfinished dependencies and open (PENDING) constraints.
            nodes.add(new DependencyGraphDTO.Node(node.taskId(), title, node.status(),
                    !node.isReady(), node.transitiveDependentsCount()));
            kept.add(node.taskId());
        }

        List<DependencyGraphDTO.Edge> edges = new ArrayList<>();
        for (ChainDependencyGraph.Edge edge : graph.edges() == null ? List.<ChainDependencyGraph.Edge>of() : graph.edges()) {
            if (kept.contains(edge.taskId()) && kept.contains(edge.dependsOnTaskId())) {
                edges.add(new DependencyGraphDTO.Edge(edge.dependsOnTaskId(), edge.taskId(),
                        edge.confidence() == null ? DEFAULT_CONFIDENCE : edge.confidence()));
            }
        }
        return new DependencyGraphDTO(nodes, edges);
    }
}
