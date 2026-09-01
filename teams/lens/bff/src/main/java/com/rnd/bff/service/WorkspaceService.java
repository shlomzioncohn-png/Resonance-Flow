package com.rnd.bff.service;

import com.rnd.bff.client.ChainClient;
import com.rnd.bff.client.InboxClient;
import com.rnd.bff.client.MindClient;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.dto.TaskDependenciesDTO;
import com.rnd.bff.dto.TaskRecommendationDTO;
import com.rnd.bff.dto.WorkspaceRequestDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates Inbox (task data), Chain (dependencies/feasibility), and Mind
 * (scoring) into a single task recommendation. All three are mocked at the
 * client level until those services expose real APIs — see
 * docs/workspace-screen-integration-plan.md, "Work Plan by Layer".
 */
@Service
public class WorkspaceService {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceService.class);

    private final InboxClient inboxClient;
    private final ChainClient chainClient;
    private final MindClient mindClient;

    public WorkspaceService(InboxClient inboxClient, ChainClient chainClient, MindClient mindClient) {
        this.inboxClient = inboxClient;
        this.chainClient = chainClient;
        this.mindClient = mindClient;
    }

    public TaskRecommendationDTO recommend(WorkspaceRequestDTO request, String userId) {
        logger.info(
                "Workspace recommendation request - userId: {}, availableTime: {} minutes",
                userId,
                request.getAvailableTime());

        List<TaskDTO> candidates = filterCandidates(inboxClient.getTasksForUser(userId), request);
        List<String> candidateIds = candidates.stream().map(TaskDTO::getId).toList();

        Map<String, TaskDependenciesDTO> feasibilityByTaskId =
                chainClient.getFeasibility(userId, candidateIds);
        Map<String, Integer> scoreByTaskId =
                mindClient.getScores(userId, candidateIds, request.getAvailableTime());

        for (TaskDTO task : candidates) {
            TaskDependenciesDTO feasibility = feasibilityByTaskId.get(task.getId());
            if (feasibility != null) {
                task.setDependencies(feasibility.getDependencies());
                task.setConstraints(feasibility.getConstraints());
            }
        }

        TaskDTO bestTask =
                candidates.stream()
                        .max(Comparator.comparing(task -> scoreByTaskId.getOrDefault(task.getId(), 0)))
                        .orElse(null);

        if (bestTask == null) {
            return TaskRecommendationDTO.builder()
                    .explanation("No matching tasks available right now.")
                    .fitsInTime(false)
                    .isHighPriority(false)
                    .hasNoDependencies(true)
                    .confidenceScore(0)
                    .alternatives(List.of())
                    .build();
        }

        boolean fitsInTime = bestTask.getEffortEstimate() <= request.getAvailableTime();
        boolean isHighPriority = "HIGH".equals(bestTask.getPriority());
        boolean hasNoDependencies = bestTask.getDependencies().isEmpty();

        List<TaskDTO> alternatives = new ArrayList<>(candidates);
        alternatives.remove(bestTask);

        return TaskRecommendationDTO.builder()
                .task(bestTask)
                .explanation(buildExplanation(request, fitsInTime, isHighPriority, hasNoDependencies))
                .fitsInTime(fitsInTime)
                .isHighPriority(isHighPriority)
                .hasNoDependencies(hasNoDependencies)
                .confidenceScore(scoreByTaskId.getOrDefault(bestTask.getId(), 0))
                .alternatives(alternatives)
                .build();
    }

    private List<TaskDTO> filterCandidates(List<TaskDTO> tasks, WorkspaceRequestDTO request) {
        return tasks.stream()
                .filter(
                        t ->
                                request.getProjectFilter() == null
                                        || request.getProjectFilter().equals(t.getProject()))
                .filter(
                        t ->
                                request.getMinPriority() == null
                                        || meetsMinPriority(t.getPriority(), request.getMinPriority()))
                .toList();
    }

    private boolean meetsMinPriority(String taskPriority, String minPriority) {
        List<String> order = List.of("LOW", "MEDIUM", "HIGH");
        return order.indexOf(taskPriority) >= order.indexOf(minPriority);
    }

    private String buildExplanation(
            WorkspaceRequestDTO request,
            boolean fitsInTime,
            boolean isHighPriority,
            boolean hasNoDependencies) {
        List<String> reasons = new ArrayList<>();
        reasons.add(
                fitsInTime
                        ? "Fits in your " + request.getAvailableTime() + " minutes"
                        : "Does not fit in your " + request.getAvailableTime() + " minutes");
        if (isHighPriority) {
            reasons.add("high importance");
        }
        if (hasNoDependencies) {
            reasons.add("no blocking dependencies");
        }
        return String.join(", ", reasons);
    }
}
