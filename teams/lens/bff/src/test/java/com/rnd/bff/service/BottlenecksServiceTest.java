package com.rnd.bff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rnd.bff.client.ChainClient;
import com.rnd.bff.client.ChainDependencyGraph;
import com.rnd.bff.client.InboxClient;
import com.rnd.bff.dto.DependencyGraphDTO;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.exception.InboxRejectedException;
import com.rnd.bff.exception.ServiceUnavailableException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class BottlenecksServiceTest {

    private static final String USER = "mgr_001";
    private static final String TENANT = "aaaaaaaa-0000-0000-0000-000000000001";

    @Mock private ChainClient chainClient;
    @Mock private InboxClient inboxClient;
    @Mock private UserTenantResolver tenantResolver;

    private BottlenecksService service;

    @BeforeEach
    void setUp() {
        service = new BottlenecksService(chainClient, inboxClient, tenantResolver);
    }

    private static ChainDependencyGraph.Node node(String id, String status, int blocking, int dependents) {
        return new ChainDependencyGraph.Node(id, status, blocking == 0, blocking, dependents, null);
    }

    private static TaskDTO task(String id, String title, String projectId) {
        return TaskDTO.builder().id(id).title(title).projectId(projectId).build();
    }

    /** ci <- api <- ui, and ci <- docs (another project). */
    private static final ChainDependencyGraph GRAPH = new ChainDependencyGraph(
            List.of(node("ci", "ACTIVE", 0, 3), node("api", "PENDING", 1, 1),
                    node("ui", "PENDING", 1, 0), node("docs", "PENDING", 1, 0)),
            List.of(new ChainDependencyGraph.Edge("api", "ci", null),
                    new ChainDependencyGraph.Edge("ui", "api", 0.4),
                    new ChainDependencyGraph.Edge("docs", "ci", null)),
            false);

    private static final List<TaskDTO> TASKS = List.of(
            task("ci", "Set up CI", "p-1"), task("api", "Build the API", "p-1"),
            task("ui", "Build the UI", "p-1"), task("docs", "Write the docs", "p-2"));

    @Test
    void mapsChainsGraphToTheFrontendsShape() {
        DependencyGraphDTO graph = BottlenecksService.toGraph(GRAPH, TASKS, null);

        assertThat(graph.nodes()).containsExactly(
                new DependencyGraphDTO.Node("ci", "Set up CI", "ACTIVE", false, 3),
                new DependencyGraphDTO.Node("api", "Build the API", "PENDING", true, 1),
                new DependencyGraphDTO.Node("ui", "Build the UI", "PENDING", true, 0),
                new DependencyGraphDTO.Node("docs", "Write the docs", "PENDING", true, 0));
        // The edge points from the blocker to the task it blocks.
        assertThat(graph.edges()).containsExactly(
                new DependencyGraphDTO.Edge("ci", "api", 1.0),
                new DependencyGraphDTO.Edge("api", "ui", 0.4),
                new DependencyGraphDTO.Edge("ci", "docs", 1.0));
    }

    @Test
    void projectFilterKeepsThatProjectsTasksAndTheEdgesBetweenThem() {
        DependencyGraphDTO graph = BottlenecksService.toGraph(GRAPH, TASKS, "p-1");

        assertThat(graph.nodes()).extracting(DependencyGraphDTO.Node::id).containsExactly("ci", "api", "ui");
        // ci still blocks docs (p-2) too, so its count stays Chain's tenant-wide 3.
        assertThat(graph.nodes().get(0).blocksCount()).isEqualTo(3);
        assertThat(graph.edges()).extracting(DependencyGraphDTO.Edge::target).containsExactly("api", "ui");
    }

    @Test
    void isBlockedFollowsChainsReadiness_soAnOpenConstraintBlocksAndClosingItUnblocks() {
        // No unfinished dependency, but not ready: an open (PENDING) constraint.
        ChainDependencyGraph withOpenConstraint = new ChainDependencyGraph(
                List.of(new ChainDependencyGraph.Node("ci", "ACTIVE", false, 0, 0, null)), List.of(), false);
        // The constraint was satisfied or waived: ready again.
        ChainDependencyGraph constraintClosed = new ChainDependencyGraph(
                List.of(new ChainDependencyGraph.Node("ci", "ACTIVE", true, 0, 0, null)), List.of(), false);

        assertThat(BottlenecksService.toGraph(withOpenConstraint, TASKS, null).nodes().get(0).isBlocked()).isTrue();
        assertThat(BottlenecksService.toGraph(constraintClosed, TASKS, null).nodes().get(0).isBlocked()).isFalse();
    }

    @Test
    void blankProjectMeansTheWholeTenant() {
        assertThat(BottlenecksService.toGraph(GRAPH, TASKS, " ").nodes()).hasSize(4);
    }

    @Test
    void aTaskInboxDoesntHaveIsUnknown_andLeftOutOfAProject() {
        List<TaskDTO> withoutDocs = TASKS.subList(0, 3);

        assertThat(BottlenecksService.toGraph(GRAPH, withoutDocs, null).nodes().get(3).title())
                .isEqualTo("Unknown task");
        assertThat(BottlenecksService.toGraph(GRAPH, withoutDocs, "p-2").nodes()).isEmpty();
    }

    @Test
    void emptyGraph() {
        DependencyGraphDTO graph = BottlenecksService.toGraph(
                new ChainDependencyGraph(null, null, false), TASKS, null);

        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.edges()).isEmpty();
    }

    @Test
    void asksChainForTheUsersOwnTenant() {
        when(tenantResolver.tenantOf(USER)).thenReturn(TENANT);
        when(chainClient.getDependencyGraph(TENANT)).thenReturn(GRAPH);
        when(inboxClient.getTenantTasks(USER)).thenReturn(TASKS);

        assertThat(service.getGraph(USER, null).nodes()).hasSize(4);
        verify(chainClient).getDependencyGraph(TENANT);
    }

    @Test
    void chainDownIs503() {
        when(tenantResolver.tenantOf(USER)).thenReturn(TENANT);
        when(chainClient.getDependencyGraph(TENANT)).thenThrow(new ResourceAccessException("refused"));

        assertThatThrownBy(() -> service.getGraph(USER, null))
                .isInstanceOf(ServiceUnavailableException.class)
                .extracting(ex -> ((ServiceUnavailableException) ex).getServiceName()).isEqualTo("chain");
    }

    @Test
    void inboxDownIs503_butARejectionStaysARejection() {
        when(tenantResolver.tenantOf(USER)).thenReturn(TENANT);
        when(chainClient.getDependencyGraph(TENANT)).thenReturn(GRAPH);
        when(inboxClient.getTenantTasks(USER))
                .thenThrow(new ResourceAccessException("refused"))
                .thenThrow(new InboxRejectedException(403, "no"));

        assertThatThrownBy(() -> service.getGraph(USER, null))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> service.getGraph(USER, null))
                .isInstanceOf(InboxRejectedException.class);
    }
}
