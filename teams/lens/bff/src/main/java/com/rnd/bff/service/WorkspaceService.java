package com.rnd.bff.service;

import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.dto.TaskRecommendationDTO;
import com.rnd.bff.dto.WorkspaceRequestDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Mock implementation until Mind (scoring), Chain (feasibility), and Inbox
 * (task data) expose their endpoints — see
 * docs/workspace-screen-integration-plan.md, "Work Plan by Layer". Returns a
 * fixed recommendation regardless of input so the Frontend can build against
 * a stable shape today.
 */
@Service
public class WorkspaceService {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceService.class);

    public TaskRecommendationDTO recommend(WorkspaceRequestDTO request, String userId) {
        logger.info(
                "Workspace recommendation request - userId: {}, availableTime: {} minutes",
                userId,
                request.getAvailableTime());

        TaskDTO task =
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
                        .dependencies(List.of())
                        .constraints(List.of())
                        .build();

        return TaskRecommendationDTO.builder()
                .task(task)
                .explanation(
                        "Fits in your " + request.getAvailableTime()
                                + " minutes, high importance, no blocking dependencies")
                .fitsInTime(task.getEffortEstimate() <= request.getAvailableTime())
                .isHighPriority("HIGH".equals(task.getPriority()))
                .hasNoDependencies(task.getDependencies().isEmpty())
                .confidenceScore(85)
                .alternatives(List.of())
                .build();
    }
}
