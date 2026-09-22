package com.rnd.inbox.taskselection;

import com.rnd.inbox.task.model.Task;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class TaskSelectionKnapsackSolver {

    private final EffortEstimateResolver effortEstimateResolver;

    public TaskSelectionKnapsackSolver(EffortEstimateResolver effortEstimateResolver) {
        this.effortEstimateResolver = effortEstimateResolver;
    }

    public List<Task> solve(Map<Task, Double> scores, int availableMinutes) {
        Solution[] bestByCapacity = new Solution[availableMinutes + 1];
        for (int capacity = 0; capacity <= availableMinutes; capacity++) {
            bestByCapacity[capacity] = Solution.empty();
        }

        for (Map.Entry<Task, Double> entry : scores.entrySet()) {
            Task task = entry.getKey();
            double value = entry.getValue();
            int weight = effortEstimateResolver.resolvedEffortEstimateMinutes(task);

            for (int capacity = availableMinutes; capacity >= weight; capacity--) {
                Solution withTask = bestByCapacity[capacity - weight].plus(task, weight, value);
                if (isBetter(withTask, bestByCapacity[capacity])) {
                    bestByCapacity[capacity] = withTask;
                }
            }
        }

        return bestByCapacity[availableMinutes].selectedTasks;
    }

    private boolean isBetter(Solution candidate, Solution current) {
        int byValue = Double.compare(candidate.totalValue, current.totalValue);
        if (byValue != 0) {
            return byValue > 0;
        }
        int byWeight = Integer.compare(candidate.totalWeight, current.totalWeight);
        if (byWeight != 0) {
            return byWeight < 0;
        }
        int byDeadline = Double.compare(candidate.averageDeadlineEpochSeconds(), current.averageDeadlineEpochSeconds());
        if (byDeadline != 0) {
            return byDeadline < 0;
        }
        return candidate.taskIdTieBreakKey().compareTo(current.taskIdTieBreakKey()) < 0;
    }

    private static final class Solution {
        private final List<Task> selectedTasks;
        private final double totalValue;
        private final int totalWeight;

        private Solution(List<Task> selectedTasks, double totalValue, int totalWeight) {
            this.selectedTasks = selectedTasks;
            this.totalValue = totalValue;
            this.totalWeight = totalWeight;
        }

        static Solution empty() {
            return new Solution(List.of(), 0, 0);
        }

        Solution plus(Task task, int weight, double value) {
            List<Task> next = new ArrayList<>(selectedTasks);
            next.add(task);
            return new Solution(next, totalValue + value, totalWeight + weight);
        }

        double averageDeadlineEpochSeconds() {
            List<Task> withDeadline = selectedTasks.stream()
                    .filter(task -> task.getDeadline() != null)
                    .toList();
            if (withDeadline.isEmpty()) {
                return Double.MAX_VALUE;
            }
            return withDeadline.stream()
                    .mapToLong(task -> task.getDeadline().getEpochSecond())
                    .average()
                    .orElse(Double.MAX_VALUE);
        }

        String taskIdTieBreakKey() {
            return selectedTasks.stream()
                    .map(task -> task.getId().toString())
                    .sorted()
                    .reduce("", String::concat);
        }
    }
}
