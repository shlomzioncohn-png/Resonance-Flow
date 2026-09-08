package com.rnd.bff.client;

import com.rnd.bff.dto.TaskDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for Inbox — owns canonical task data (see
 * docs/lens-architecture-design.md). Inbox's API isn't live yet, so this
 * holds an in-memory mock task set instead of calling out anywhere. The
 * method signatures (userId in, task data out) are what the real HTTP calls
 * will use, so wiring them up later is a body swap, not a redesign.
 *
 * Ignores userId for now (single mock tenant) — every method still takes it
 * so callers don't change when Inbox becomes real and starts scoping by user.
 *
 * Backed by a mutable LinkedHashMap (not List.of(...) per call) so
 * updateStatus() actually persists for the lifetime of this instance —
 * getTask() after updateStatus() reflects the change, which a fresh literal
 * per call could never do.
 */
@Service
public class InboxClient {

    @Value("${inbox.service.url:http://inbox-service:8080}")
    private String inboxServiceUrl;

    private final Map<String, TaskDTO> tasksById = new LinkedHashMap<>();

    public InboxClient() {
        seed(TaskDTO.builder()
                .id("task_001")
                .title("Review API changes")
                .description("Review the latest API changes and update integration tests.")
                .status("READY")
                .priority("HIGH")
                .effortEstimate(35)
                .dueDate(LocalDateTime.of(2026, 8, 30, 17, 0))
                .owner("Alex Morgan")
                .project("Platform Service")
                .build());
        seed(TaskDTO.builder()
                .id("task_002")
                .title("Update customer proposal")
                .description("Incorporate legal's redlines into the Q3 proposal draft.")
                .status("BLOCKED")
                .priority("HIGH")
                .effortEstimate(60)
                .dueDate(LocalDateTime.of(2026, 9, 6, 17, 0))
                .owner("Alex Morgan")
                .project("Customer Success")
                .constraints(List.of("approval_needed"))
                .build());
        seed(TaskDTO.builder()
                .id("task_003")
                .title("Prepare contract draft")
                .description("Draft the renewal contract for the Acme account.")
                .status("BLOCKED")
                .priority("MEDIUM")
                .effortEstimate(90)
                .dueDate(LocalDateTime.of(2026, 9, 7, 17, 0))
                .owner("Alex Morgan")
                .project("Customer Success")
                .constraints(List.of("waiting_for_customer"))
                .build());
        seed(TaskDTO.builder()
                .id("task_004")
                .title("Update documentation")
                .description("Bring the API docs in line with the latest endpoint changes.")
                .status("IN_PROGRESS")
                .priority("LOW")
                .effortEstimate(45)
                .dueDate(LocalDateTime.of(2026, 9, 12, 17, 0))
                .owner("Alex Morgan")
                .project("Platform Service")
                .build());
        seed(TaskDTO.builder()
                .id("task_005")
                .title("Fix login bug")
                .description("Users on Safari intermittently can't complete SSO login.")
                .status("READY")
                .priority("HIGH")
                .effortEstimate(20)
                .dueDate(LocalDateTime.of(2026, 9, 6, 12, 0))
                .owner("Alex Morgan")
                .project("Auth Service")
                .build());
        seed(TaskDTO.builder()
                .id("task_006")
                .title("Write unit tests for billing")
                .description("Cover the new proration logic added last sprint.")
                .status("IN_PROGRESS")
                .priority("MEDIUM")
                .effortEstimate(50)
                .dueDate(LocalDateTime.of(2026, 9, 15, 17, 0))
                .owner("Alex Morgan")
                .project("Billing")
                .build());
    }

    private void seed(TaskDTO task) {
        tasksById.put(task.getId(), task);
    }

    // TODO: replace with GET {inboxServiceUrl}/api/v1/tasks?userId=... once Inbox exposes it
    public List<TaskDTO> getTasksForUser(String userId) {
        return new ArrayList<>(tasksById.values());
    }

    // TODO: replace with GET {inboxServiceUrl}/api/v1/tasks/{taskId} once Inbox exposes it
    public TaskDTO getTaskById(String userId, String taskId) {
        return tasksById.get(taskId);
    }

    // TODO: replace with PATCH {inboxServiceUrl}/api/v1/tasks/{taskId} once Inbox exposes it
    public TaskDTO updateStatus(String userId, String taskId, String newStatus) {
        TaskDTO task = tasksById.get(taskId);
        if (task == null) {
            return null;
        }
        task.setStatus(newStatus);
        return task;
    }
}
