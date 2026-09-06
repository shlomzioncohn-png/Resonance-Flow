package com.rnd.bff.client;

import com.rnd.bff.dto.TaskDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Client for Inbox — owns canonical task data (see
 * docs/lens-architecture-design.md). Inbox's API isn't live yet, so this
 * returns a fixed mock. The method signature (userId in, candidate tasks
 * out) is what the real call will use, so wiring it up later is a body
 * swap, not a redesign.
 */
@Service
public class InboxClient {

    @Value("${inbox.service.url:http://inbox-service:8080}")
    private String inboxServiceUrl;

    // TODO: replace with GET {inboxServiceUrl}/api/v1/tasks?userId=... once Inbox exposes it
    public List<TaskDTO> getTasksForUser(String userId) {
        return List.of(
                TaskDTO.builder()
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
    }
}
