package com.rnd.bff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bff.client.ChainClient;
import com.rnd.bff.client.InboxClient;
import com.rnd.bff.dto.ChainFlowHealthFlagDTO;
import com.rnd.bff.dto.FlowHealthDTO;
import com.rnd.bff.dto.FlowHealthFlagDTO;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.exception.ServiceUnavailableException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class FlowHealthServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String T1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String T2 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String T3 = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String UNKNOWN = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String PROJECT_A = "11111111-1111-1111-1111-111111111111";
    private static final String PROJECT_B = "22222222-2222-2222-2222-222222222222";

    @Mock private ChainClient chainClient;
    @Mock private InboxClient inboxClient;
    @InjectMocks private FlowHealthService service;

    private static TaskDTO task(String id, String title, String projectId, String projectName) {
        return TaskDTO.builder().id(id).title(title).projectId(projectId).project(projectName).build();
    }

    private static ChainFlowHealthFlagDTO flag(String id, String pattern, String entityType, List<String> entityIds,
                                               int rank, String runAt, String evidenceJson) {
        try {
            return new ChainFlowHealthFlagDTO(id, pattern, entityType, entityIds, "high", 0.9, rank, "why",
                    MAPPER.readTree(evidenceJson), runAt);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void getFlowHealth_namesTasksForTaskEdgeAndChainEntities() {
        when(chainClient.getFlowHealthFlags()).thenReturn(List.of(
                flag("CHRONIC_BLOCKER:1", "CHRONIC_BLOCKER", "task", List.of(T1), 1, "2026-10-28T10:00:00Z", "{}"),
                flag("RECURRING_CYCLE:2", "RECURRING_CYCLE", "edge", List.of(T2 + ">" + T1), 2,
                        "2026-10-28T10:00:00Z", "{}"),
                flag("SLOW_CHAIN:3", "SLOW_CHAIN", "chain", List.of(T3, T2, T1), 3, "2026-10-28T10:00:00Z", "{}")));
        when(inboxClient.getTenantTasks("mgr")).thenReturn(List.of(
                task(T1, "Set up DB", PROJECT_A, "Platform"),
                task(T2, "Deploy API", PROJECT_A, "Platform"),
                task(T3, "Launch", PROJECT_B, "Billing")));

        FlowHealthDTO result = service.getFlowHealth("mgr");

        List<FlowHealthFlagDTO> flags = result.getFlags();
        assertThat(flags.get(0).getEntities().get(0).getLabel()).isEqualTo("Set up DB");
        assertThat(flags.get(1).getEntities().get(0).getId()).isEqualTo(T2 + ">" + T1);
        assertThat(flags.get(1).getEntities().get(0).getLabel()).isEqualTo("Deploy API → Set up DB");
        assertThat(flags.get(1).getEntities().get(0).getTasks()).extracting("id").containsExactly(T2, T1);
        assertThat(flags.get(2).getEntities()).extracting("label").containsExactly("Launch", "Deploy API", "Set up DB");
        assertThat(flags).extracting(FlowHealthFlagDTO::getFlagId)
                .containsExactly("CHRONIC_BLOCKER:1", "RECURRING_CYCLE:2", "SLOW_CHAIN:3");
    }

    @Test
    void getFlowHealth_unknownTaskFallsBackToItsId() {
        when(chainClient.getFlowHealthFlags()).thenReturn(List.of(
                flag("CHRONIC_BLOCKER:1", "CHRONIC_BLOCKER", "task", List.of(UNKNOWN), 1, "2026-10-28T10:00:00Z", "{}")));
        when(inboxClient.getTenantTasks("mgr")).thenReturn(List.of());

        FlowHealthDTO result = service.getFlowHealth("mgr");

        assertThat(result.getFlags().get(0).getEntities().get(0).getLabel()).isEqualTo(UNKNOWN);
        assertThat(result.getFlags().get(0).getProjects()).isEmpty();
    }

    @Test
    void getFlowHealth_countsEveryPatternAndTakesTheNewestRunDate() {
        when(chainClient.getFlowHealthFlags()).thenReturn(List.of(
                flag("a", "CHRONIC_BLOCKER", "task", List.of(T1), 1, "2026-10-27T10:00:00Z", "{}"),
                flag("b", "CHRONIC_BLOCKER", "task", List.of(T2), 2, "2026-10-28T10:00:00Z", "{}"),
                flag("c", "SLOW_CHAIN", "chain", List.of(T1, T2), 3, "2026-10-26T10:00:00Z", "{}")));
        when(inboxClient.getTenantTasks("mgr")).thenReturn(List.of());

        FlowHealthDTO result = service.getFlowHealth("mgr");

        assertThat(result.getCounts()).containsEntry("CHRONIC_BLOCKER", 2)
                .containsEntry("RECURRING_CYCLE", 0).containsEntry("SLOW_CHAIN", 1);
        assertThat(result.getLastRunAt()).isEqualTo("2026-10-28T10:00:00Z");
    }

    @Test
    void getFlowHealth_collectsProjectsPerFlagAndOverall() {
        when(chainClient.getFlowHealthFlags()).thenReturn(List.of(
                flag("a", "SLOW_CHAIN", "chain", List.of(T1, T3), 1, "2026-10-28T10:00:00Z", "{}"),
                flag("b", "CHRONIC_BLOCKER", "task", List.of(T2), 2, "2026-10-28T10:00:00Z", "{}")));
        when(inboxClient.getTenantTasks("mgr")).thenReturn(List.of(
                task(T1, "One", PROJECT_A, "Platform"), task(T2, "Two", PROJECT_A, "Platform"),
                task(T3, "Three", PROJECT_B, "Billing")));

        FlowHealthDTO result = service.getFlowHealth("mgr");

        assertThat(result.getFlags().get(0).getProjects()).extracting("id").containsExactly(PROJECT_A, PROJECT_B);
        assertThat(result.getFlags().get(1).getProjects()).extracting("name").containsExactly("Platform");
        assertThat(result.getProjects()).extracting("id").containsExactly(PROJECT_A, PROJECT_B);
    }

    @Test
    void getFlowHealth_taskNamesAlsoCoverTaskIdsInsideTheEvidence() {
        when(chainClient.getFlowHealthFlags()).thenReturn(List.of(
                flag("a", "CHRONIC_BLOCKER", "task", List.of(T1), 1, "2026-10-28T10:00:00Z",
                        "{\"blocked\": [{\"taskId\": \"" + T2 + "\", \"days\": 4}], \"note\": \"no id here\"}")));
        when(inboxClient.getTenantTasks("mgr")).thenReturn(List.of(
                task(T1, "Blocker", PROJECT_A, "Platform"), task(T2, "Blocked one", PROJECT_A, "Platform")));

        FlowHealthDTO result = service.getFlowHealth("mgr");

        assertThat(result.getTaskNames()).containsEntry(T1, "Blocker").containsEntry(T2, "Blocked one");
        assertThat(result.getFlags().get(0).getEvidence().get("blocked").get(0).get("days").asInt()).isEqualTo(4);
    }

    @Test
    void getFlowHealth_noFlags_returnsEmptyStateWithoutCallingInbox() {
        when(chainClient.getFlowHealthFlags()).thenReturn(List.of());

        FlowHealthDTO result = service.getFlowHealth("mgr");

        assertThat(result.getFlags()).isEmpty();
        assertThat(result.getLastRunAt()).isNull();
        assertThat(result.getCounts().values()).containsOnly(0);
        verifyNoInteractions(inboxClient);
    }

    @Test
    void getFlowHealth_chainUnavailable_propagatesServiceUnavailable() {
        when(chainClient.getFlowHealthFlags()).thenThrow(new ServiceUnavailableException("chain", "down"));

        assertThatThrownBy(() -> service.getFlowHealth("mgr")).isInstanceOf(ServiceUnavailableException.class);
        verifyNoInteractions(inboxClient);
    }

    @Test
    void getFlowHealth_inboxUnreachable_becomesServiceUnavailable() {
        when(chainClient.getFlowHealthFlags()).thenReturn(List.of(
                flag("a", "SLOW_CHAIN", "chain", List.of(T1), 1, "2026-10-28T10:00:00Z", "{}")));
        when(inboxClient.getTenantTasks("mgr")).thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> service.getFlowHealth("mgr")).isInstanceOf(ServiceUnavailableException.class);
    }
}
