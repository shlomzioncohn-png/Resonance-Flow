package com.rnd.bff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rnd.bff.client.ChainClient;
import com.rnd.bff.client.ChainDependencyHistoryEntry;
import com.rnd.bff.client.ChainStateHistoryEntry;
import com.rnd.bff.client.InboxClient;
import com.rnd.bff.client.InboxUserClient;
import com.rnd.bff.client.InboxUserRecord;
import com.rnd.bff.dto.TaskActivityDTO;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.exception.ServiceUnavailableException;
import com.rnd.bff.exception.TaskNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class TaskActivityServiceTest {

    private static final String USER = "emp_001";
    private static final String DEV_ACTOR = "00000000-0000-0000-0000-000000000001";
    private static final String TENANT = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String TASK = "7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c01";
    private static final String DEP_A = "7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c0a";
    private static final String DEP_B = "7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c0b";

    @Mock private ChainClient chainClient;
    @Mock private InboxClient inboxClient;
    @Mock private InboxUserClient inboxUserClient;

    private TaskActivityService service;

    @BeforeEach
    void setUp() {
        service = new TaskActivityService(chainClient, inboxClient, inboxUserClient, DEV_ACTOR);
        InboxUserRecord.TenantSummary tenant = new InboxUserRecord.TenantSummary();
        tenant.setId(TENANT);
        InboxUserRecord user = new InboxUserRecord();
        user.setTenant(tenant);
        lenient().when(inboxUserClient.getUser(DEV_ACTOR, DEV_ACTOR)).thenReturn(user);
    }

    private static ChainStateHistoryEntry status(String id, String from, String to, String at) {
        return new ChainStateHistoryEntry(id, TASK, "STATUS_CHANGED", from, to, null, null, Instant.parse(at));
    }

    private static ChainStateHistoryEntry blocking(String id, String type, int before, int after, String at) {
        return new ChainStateHistoryEntry(id, TASK, type, null, null, before, after, Instant.parse(at));
    }

    private static ChainDependencyHistoryEntry dependency(String id, String dependsOn, String action, String source,
                                                          String at) {
        return new ChainDependencyHistoryEntry(id, TASK, dependsOn, action, source, null, Instant.parse(at));
    }

    private static TaskDTO task(String title) {
        return TaskDTO.builder().title(title).build();
    }

    // --- merge and sort ---

    @Test
    void mergesBothHistoriesNewestFirst() {
        List<TaskActivityDTO.Item> items = TaskActivityService.merge(
                List.of(status("s1", "PENDING", "ACTIVE", "2026-10-20T09:15:30Z"),
                        blocking("s2", "BLOCKED", 0, 2, "2026-10-19T14:02:11Z")),
                List.of(dependency("d1", DEP_A, "ADDED", "USER", "2026-10-19T20:00:00Z"),
                        dependency("d2", DEP_A, "REMOVED", "MIND", "2026-10-21T08:00:00Z")),
                Map.of(DEP_A, "Write the API spec"));

        assertThat(items).extracting(TaskActivityDTO.Item::getId).containsExactly("d2", "s1", "d1", "s2");
        assertThat(items).extracting(TaskActivityDTO.Item::getType)
                .containsExactly("DEPENDENCY_REMOVED", "STATUS_CHANGED", "DEPENDENCY_ADDED", "BLOCKED");
    }

    @Test
    void twoEventsAtTheSameMomentAreBothKeptInAStableOrder() {
        String sameMoment = "2026-10-20T09:15:30Z";
        List<TaskActivityDTO.Item> items = TaskActivityService.merge(
                List.of(status("s1", "PENDING", "ACTIVE", sameMoment)),
                List.of(dependency("d1", DEP_A, "ADDED", "SYSTEM", sameMoment)),
                Map.of(DEP_A, "Write the API spec"));

        assertThat(items).hasSize(2);
        // Same createdAt: the state event first, then the dependency event - every time.
        assertThat(items).extracting(TaskActivityDTO.Item::getId).containsExactly("s1", "d1");
    }

    @Test
    void sameMomentSameKindIsOrderedById() {
        String sameMoment = "2026-10-20T09:15:30Z";
        List<TaskActivityDTO.Item> items = TaskActivityService.merge(
                List.of(status("s2", "ACTIVE", "DONE", sameMoment), status("s1", "PENDING", "ACTIVE", sameMoment)),
                List.of(), Map.of());

        assertThat(items).extracting(TaskActivityDTO.Item::getId).containsExactly("s1", "s2");
    }

    @Test
    void eachItemCarriesOnlyItsOwnFields() {
        List<TaskActivityDTO.Item> items = TaskActivityService.merge(
                List.of(status("s1", "PENDING", "ACTIVE", "2026-10-20T09:00:00Z"),
                        blocking("s2", "UNBLOCKED", 2, 0, "2026-10-19T09:00:00Z")),
                List.of(dependency("d1", DEP_A, "ADDED", "MIND", "2026-10-18T09:00:00Z")),
                Map.of(DEP_A, "Write the API spec"));

        TaskActivityDTO.Item statusChange = items.get(0);
        assertThat(statusChange.getFromStatus()).isEqualTo("PENDING");
        assertThat(statusChange.getToStatus()).isEqualTo("ACTIVE");
        assertThat(statusChange.getBlockingCount()).isNull();
        assertThat(statusChange.getDependsOnTitle()).isNull();

        TaskActivityDTO.Item unblocked = items.get(1);
        assertThat(unblocked.getBlockingCount()).isZero();
        assertThat(unblocked.getFromStatus()).isNull();

        TaskActivityDTO.Item added = items.get(2);
        assertThat(added.getDependsOnTitle()).isEqualTo("Write the API spec");
        assertThat(added.getSource()).isEqualTo("MIND");
        assertThat(added.getFromStatus()).isNull();
    }

    @Test
    void dependencyWithoutAKnownTitleSaysUnknownTask() {
        List<TaskActivityDTO.Item> items = TaskActivityService.merge(
                List.of(), List.of(dependency("d1", DEP_B, "ADDED", "USER", "2026-10-18T09:00:00Z")), Map.of());

        assertThat(items.get(0).getDependsOnTitle()).isEqualTo(TaskActivityService.UNKNOWN_TASK_TITLE);
    }

    // --- getActivity: tenant, titles, partial failure ---

    @Test
    void sendsTheSignedInUsersTenantToBothChainCalls() {
        when(chainClient.getStateHistory(TASK, TENANT)).thenReturn(List.of());
        when(chainClient.getDependencyHistory(TASK, TENANT)).thenReturn(List.of());

        TaskActivityDTO activity = service.getActivity(USER, TASK);

        verify(chainClient).getStateHistory(TASK, TENANT);
        verify(chainClient).getDependencyHistory(TASK, TENANT);
        assertThat(activity.getItems()).isEmpty();
        assertThat(activity.isStatusHistoryUnavailable()).isFalse();
        assertThat(activity.isDependencyHistoryUnavailable()).isFalse();
    }

    @Test
    void looksUpEachDependencyTitleOnceFromInbox() {
        when(chainClient.getStateHistory(TASK, TENANT)).thenReturn(List.of());
        when(chainClient.getDependencyHistory(TASK, TENANT)).thenReturn(List.of(
                dependency("d1", DEP_A, "ADDED", "USER", "2026-10-18T09:00:00Z"),
                dependency("d2", DEP_A, "REMOVED", "USER", "2026-10-19T09:00:00Z"),
                dependency("d3", DEP_B, "ADDED", "SYSTEM", "2026-10-20T09:00:00Z")));
        when(inboxClient.getTaskById(USER, DEP_A)).thenReturn(task("Write the API spec"));
        when(inboxClient.getTaskById(USER, DEP_B)).thenThrow(new ResourceAccessException("Inbox down"));

        TaskActivityDTO activity = service.getActivity(USER, TASK);

        verify(inboxClient, times(1)).getTaskById(USER, DEP_A);
        assertThat(activity.getItems()).extracting(TaskActivityDTO.Item::getDependsOnTitle)
                .containsExactly(TaskActivityService.UNKNOWN_TASK_TITLE, "Write the API spec", "Write the API spec");
    }

    @Test
    void dependencyHistoryFailingStillReturnsTheStatusHistory() {
        when(chainClient.getStateHistory(TASK, TENANT))
                .thenReturn(List.of(status("s1", "PENDING", "ACTIVE", "2026-10-20T09:00:00Z")));
        when(chainClient.getDependencyHistory(TASK, TENANT)).thenThrow(new ResourceAccessException("timeout"));

        TaskActivityDTO activity = service.getActivity(USER, TASK);

        assertThat(activity.getItems()).extracting(TaskActivityDTO.Item::getId).containsExactly("s1");
        assertThat(activity.isStatusHistoryUnavailable()).isFalse();
        assertThat(activity.isDependencyHistoryUnavailable()).isTrue();
        verify(inboxClient, never()).getTaskById(anyString(), anyString());
    }

    @Test
    void statusHistoryFailingStillReturnsTheDependencyHistory() {
        when(chainClient.getStateHistory(TASK, TENANT)).thenThrow(new RuntimeException("500"));
        when(chainClient.getDependencyHistory(TASK, TENANT))
                .thenReturn(List.of(dependency("d1", DEP_A, "ADDED", "USER", "2026-10-18T09:00:00Z")));
        when(inboxClient.getTaskById(USER, DEP_A)).thenReturn(task("Write the API spec"));

        TaskActivityDTO activity = service.getActivity(USER, TASK);

        assertThat(activity.getItems()).extracting(TaskActivityDTO.Item::getId).containsExactly("d1");
        assertThat(activity.isStatusHistoryUnavailable()).isTrue();
        assertThat(activity.isDependencyHistoryUnavailable()).isFalse();
    }

    @Test
    void bothFailingIsServiceUnavailable() {
        when(chainClient.getStateHistory(any(), any())).thenThrow(new ResourceAccessException("down"));
        when(chainClient.getDependencyHistory(any(), any())).thenThrow(new ResourceAccessException("down"));

        assertThatThrownBy(() -> service.getActivity(USER, TASK)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void notAUuidIsNotFoundWithoutCallingChain() {
        assertThatThrownBy(() -> service.getActivity(USER, "task_001")).isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(chainClient);
    }

    @Test
    void userWithoutATenantIsServiceUnavailable() {
        when(inboxUserClient.getUser(DEV_ACTOR, DEV_ACTOR)).thenReturn(new InboxUserRecord());

        assertThatThrownBy(() -> service.getActivity(USER, TASK)).isInstanceOf(ServiceUnavailableException.class);
        verifyNoInteractions(chainClient);
    }

    @Test
    void withoutADevActorTheGatewayUserIsTheInboxActor() {
        TaskActivityService plain = new TaskActivityService(chainClient, inboxClient, inboxUserClient, "");
        when(inboxUserClient.getUser(USER, USER)).thenReturn(null);

        assertThatThrownBy(() -> plain.getActivity(USER, TASK)).isInstanceOf(ServiceUnavailableException.class);
        verify(inboxUserClient).getUser(USER, USER);
    }
}
