package com.rnd.bff.service;

import com.rnd.bff.client.ChainClient;
import com.rnd.bff.client.InboxClient;
import com.rnd.bff.client.MindClient;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.dto.TaskDependenciesDTO;
import com.rnd.bff.dto.TaskRecommendationDTO;
import com.rnd.bff.dto.WorkspaceRequestDTO;
import com.rnd.bff.exception.ServiceUnavailableException;
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

        List<TaskDTO> candidates;
        try {
            candidates = filterCandidates(inboxClient.getTasksForUser(userId), request);
        } catch (RuntimeException ex) {
            // Inbox owns canonical task data - with no task list, no recommendation
            // is possible, so this fails the request rather than degrading.
            throw new ServiceUnavailableException("Inbox", "Inbox is unavailable", ex);
        }
        List<String> candidateIds = candidates.stream().map(TaskDTO::getId).toList();

        Map<String, TaskDependenciesDTO> feasibilityByTaskId;
        boolean chainAvailable = true;
        try {
            feasibilityByTaskId = chainClient.getFeasibility(userId, candidateIds);
        } catch (RuntimeException ex) {
            // Chain is down: a recommendation is still possible, just without
            // dependency data. Treat every task's dependency status as unknown
            // rather than failing the whole request.
            logger.warn("Chain unavailable, proceeding without dependency data - userId: {}", userId, ex);
            feasibilityByTaskId = Map.of();
            chainAvailable = false;
        }

        Map<String, Integer> scoreByTaskId;
        boolean mindAvailable = true;
        try {
            scoreByTaskId = mindClient.getScores(userId, candidateIds, request.getAvailableTime());
        } catch (RuntimeException ex) {
            // Mind is down: a recommendation is still possible, just unranked.
            // Every candidate falls back to the same neutral score below.
            logger.warn("Mind unavailable, proceeding without scores - userId: {}", userId, ex);
            scoreByTaskId = Map.of();
            mindAvailable = false;
        }

        for (TaskDTO task : candidates) {
            // Chain may not have an entry for every task even when it's up -
            // it's a partial map, not an edge case - so dependencies/constraints
            // stay null (unknown) rather than defaulting to "none".
            TaskDependenciesDTO feasibility = feasibilityByTaskId.get(task.getId());
            if (feasibility != null) {
                task.setDependencies(feasibility.getDependencies());
                task.setConstraints(feasibility.getConstraints());
            }
        }

        Map<String, Integer> finalScoreByTaskId = scoreByTaskId;
        TaskDTO bestTask =
                candidates.stream()
                        .max(Comparator.comparing(task -> finalScoreByTaskId.getOrDefault(task.getId(), 0)))
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

        // An unknown effort estimate can't be confirmed to fit - false is the
        // conservative read, matching the unknown-dependencies handling below.
        boolean fitsInTime =
                bestTask.getEffortEstimate() != null
                        && bestTask.getEffortEstimate() <= request.getAvailableTime();
        boolean isHighPriority = "HIGH".equals(bestTask.getPriority());
        // "No dependencies" requires Chain to have actually said so; unknown
        // (Chain down, or no entry for this task) is not the same as "none".
        boolean hasNoDependencies =
                chainAvailable
                        && bestTask.getDependencies() != null
                        && bestTask.getDependencies().isEmpty();

        List<TaskDTO> alternatives = new ArrayList<>(candidates);
        alternatives.remove(bestTask);

        return TaskRecommendationDTO.builder()
                .task(bestTask)
                .explanation(
                        buildExplanation(
                                request, fitsInTime, isHighPriority, hasNoDependencies, chainAvailable, mindAvailable))
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
            boolean hasNoDependencies,
            boolean chainAvailable,
            boolean mindAvailable) {
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
        if (!chainAvailable) {
            reasons.add("dependency data unavailable");
        }
        if (!mindAvailable) {
            reasons.add("ranking unavailable, showing default order");
        }
        return String.join(", ", reasons);
    }
}
