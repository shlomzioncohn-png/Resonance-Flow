package com.rnd.bff.service;

import com.rnd.bff.client.ChainClient;
import com.rnd.bff.client.ChainDependencyHistoryEntry;
import com.rnd.bff.client.ChainStateHistoryEntry;
import com.rnd.bff.client.InboxClient;
import com.rnd.bff.dto.TaskActivityDTO;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.exception.ServiceUnavailableException;
import com.rnd.bff.exception.TaskNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The employee task page's Activity tab: Chain's status/blocking history and
 * its dependency history, merged into one list, newest first.
 *
 * - The tenant sent to Chain (X-Tenant-Id) is the signed-in user's own, read
 *   from their Inbox record - never a parameter from the frontend.
 * - Dependency rows get the depended-on task's title from Inbox (the same task
 *   fetch the page itself uses), so the row shows a title, not an id.
 * - If one history fails, the other is still returned, with a flag saying
 *   which part is missing. Only when both fail is the whole request an error.
 */
@Service
public class TaskActivityService {

    private static final Logger logger = LoggerFactory.getLogger(TaskActivityService.class);

    static final String UNKNOWN_TASK_TITLE = "Unknown task";

    /** Same moment: state events before dependency events, then by id - so the order never flickers. */
    static final Comparator<TaskActivityDTO.Item> NEWEST_FIRST = Comparator
            .comparing(TaskActivityDTO.Item::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(item -> item.getType() != null && item.getType().startsWith("DEPENDENCY_") ? 1 : 0)
            .thenComparing(TaskActivityDTO.Item::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final ChainClient chainClient;
    private final InboxClient inboxClient;
    private final UserTenantResolver tenantResolver;

    public TaskActivityService(ChainClient chainClient, InboxClient inboxClient, UserTenantResolver tenantResolver) {
        this.chainClient = chainClient;
        this.inboxClient = inboxClient;
        this.tenantResolver = tenantResolver;
    }

    public TaskActivityDTO getActivity(String userId, String taskId) {
        if (!isUuid(taskId)) {
            throw new TaskNotFoundException(taskId);
        }
        String tenantId = tenantResolver.tenantOf(userId);

        List<ChainStateHistoryEntry> states = null;
        List<ChainDependencyHistoryEntry> dependencies = null;
        try {
            states = chainClient.getStateHistory(taskId, tenantId);
        } catch (RuntimeException ex) {
            logger.warn("Chain state history unavailable - taskId: {}", taskId, ex);
        }
        try {
            dependencies = chainClient.getDependencyHistory(taskId, tenantId);
        } catch (RuntimeException ex) {
            logger.warn("Chain dependency history unavailable - taskId: {}", taskId, ex);
        }
        if (states == null && dependencies == null) {
            throw new ServiceUnavailableException("chain", "Task history is unavailable");
        }

        Map<String, String> titles = dependencies == null ? Map.of() : titlesOf(userId, dependencies);
        return TaskActivityDTO.builder()
                .items(merge(states == null ? List.of() : states,
                        dependencies == null ? List.of() : dependencies, titles))
                .statusHistoryUnavailable(states == null)
                .dependencyHistoryUnavailable(dependencies == null)
                .build();
    }

    /** Both histories as one list, newest first. Two events at the same moment are both kept. */
    static List<TaskActivityDTO.Item> merge(List<ChainStateHistoryEntry> states,
                                            List<ChainDependencyHistoryEntry> dependencies,
                                            Map<String, String> titles) {
        List<TaskActivityDTO.Item> items = new ArrayList<>(states.size() + dependencies.size());
        for (ChainStateHistoryEntry entry : states) {
            TaskActivityDTO.Item.ItemBuilder item = TaskActivityDTO.Item.builder()
                    .id(entry.id())
                    .type(entry.eventType())
                    .createdAt(entry.createdAt());
            if ("STATUS_CHANGED".equals(entry.eventType())) {
                item.fromStatus(entry.fromStatus()).toStatus(entry.toStatus());
            } else {
                item.blockingCount(entry.blockingCountAfter());
            }
            items.add(item.build());
        }
        for (ChainDependencyHistoryEntry entry : dependencies) {
            items.add(TaskActivityDTO.Item.builder()
                    .id(entry.id())
                    .type("DEPENDENCY_" + entry.action())
                    .createdAt(entry.createdAt())
                    .dependsOnTaskId(entry.dependsOnTaskId())
                    .dependsOnTitle(titles.getOrDefault(entry.dependsOnTaskId(), UNKNOWN_TASK_TITLE))
                    .source(entry.source())
                    .build());
        }
        items.sort(NEWEST_FIRST);
        return items;
    }

    /** One Inbox lookup per distinct depended-on task; a task Inbox can't give us is "Unknown task". */
    private Map<String, String> titlesOf(String userId, List<ChainDependencyHistoryEntry> dependencies) {
        Map<String, String> titles = new LinkedHashMap<>();
        dependencies.stream().map(ChainDependencyHistoryEntry::dependsOnTaskId).filter(Objects::nonNull).distinct()
                .forEach(id -> {
                    try {
                        TaskDTO task = inboxClient.getTaskById(userId, id);
                        titles.put(id, task != null && task.getTitle() != null ? task.getTitle() : UNKNOWN_TASK_TITLE);
                    } catch (RuntimeException ex) {
                        logger.warn("Couldn't load the title of dependency task {}", id, ex);
                        titles.put(id, UNKNOWN_TASK_TITLE);
                    }
                });
        return titles;
    }

    private static boolean isUuid(String id) {
        try {
            UUID.fromString(id);
            return true;
        } catch (IllegalArgumentException | NullPointerException ex) {
            return false;
        }
    }
}
