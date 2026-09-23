package com.rnd.bff.client;

import com.rnd.bff.dto.EmployeeTaskDTO;
import com.rnd.bff.dto.TaskRefDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The employee's current task, for the My Workspace chat/blocking-dependency
 * demo (see docs/lens-architecture-design.md). Unlike InboxClient/ChainClient/
 * MindClient, this isn't a stand-in for one specific downstream service —
 * no service owns "my current task" yet (see frontend's employeeApi.ts) — so
 * this returns the same fixed mock the frontend previously mocked itself via
 * MSW, moved here so the frontend can call a real endpoint instead.
 *
 * TODO: replace with a real call once Inbox (or whichever service ends up
 * owning this view) exposes it.
 */
@Service
public class EmployeeClient {

    public EmployeeTaskDTO getMyTask(String userId) {
        return EmployeeTaskDTO.builder()
                .id("task-1")
                .title("Review API changes")
                .team("Platform Service")
                .priority("High")
                .status("Blocked")
                .description("Review the latest API changes and update integration tests accordingly.")
                .owner("Rachel")
                .due("Today, 5:00 PM")
                .estimatedMinutes(35)
                .createdAt("May 10, 9:15 AM")
                .source("GitHub — PR #4821")
                .sprint("Sprint 24 (May 6 – May 20)")
                .tags(List.of("backend", "api", "testing"))
                .blockedBy(List.of(
                        TaskRefDTO.builder()
                                .id("task-0")
                                .title("Update API specification")
                                .owner("Alex Morgan")
                                .build()))
                .blocks(List.of(
                        TaskRefDTO.builder()
                                .id("task-2")
                                .title("Run regression tests")
                                .owner("Taylor Smith")
                                .build(),
                        TaskRefDTO.builder()
                                .id("task-3")
                                .title("Update client SDK")
                                .owner("Sam Cohen")
                                .build()))
                .blockingReason("Waiting for the API specification to be finalized.")
                .build();
    }
}
