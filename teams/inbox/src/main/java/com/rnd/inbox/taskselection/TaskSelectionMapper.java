package com.rnd.inbox.taskselection;

import com.rnd.inbox.task.Task;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class TaskSelectionMapper {

    private final EffortEstimateResolver effortEstimateResolver;

    public TaskSelectionMapper(EffortEstimateResolver effortEstimateResolver) {
        this.effortEstimateResolver = effortEstimateResolver;
    }

    public TaskSelectionResponseDto toResponseDto(Map<Task, Double> selectedScores,
                                                    int availableMinutes, boolean usedFallback, String reason) {
        List<Task> executionOrder = selectedScores.keySet().stream()
                .sorted(Comparator
                        .comparing(this::deadlineOrMax)
                        .thenComparing(selectedScores::get, Comparator.reverseOrder()))
                .toList();

        List<SelectedTaskDto> selectedTaskDtos = executionOrder.stream()
                .map(task -> toSelectedTaskDto(task, selectedScores.get(task)))
                .toList();

        int totalEffortMinutes = executionOrder.stream()
                .mapToInt(effortEstimateResolver::resolvedEffortEstimateMinutes)
                .sum();
        double totalValue = executionOrder.stream()
                .mapToDouble(selectedScores::get)
                .sum();

        return new TaskSelectionResponseDto(
                selectedTaskDtos,
                totalEffortMinutes,
                totalValue,
                availableMinutes - totalEffortMinutes,
                usedFallback,
                reason);
    }

    public TaskSelectionResponseDto noTasksAvailable(int availableMinutes) {
        return new TaskSelectionResponseDto(List.of(), 0, 0, availableMinutes, false, "No tasks available");
    }

    private SelectedTaskDto toSelectedTaskDto(Task task, double score) {
        return new SelectedTaskDto(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                effortEstimateResolver.resolvedEffortEstimateMinutes(task),
                task.getDeadline(),
                score);
    }

    private Instant deadlineOrMax(Task task) {
        return task.getDeadline() != null ? task.getDeadline() : Instant.MAX;
    }
}
