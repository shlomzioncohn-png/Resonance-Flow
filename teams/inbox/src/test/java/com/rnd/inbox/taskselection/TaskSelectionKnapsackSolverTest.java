package com.rnd.inbox.taskselection;

import com.rnd.inbox.task.model.Task;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskSelectionKnapsackSolverTest {

    private static final Instant NOW = Instant.parse("2026-06-15T09:00:00Z");

    private final TaskSelectionKnapsackSolver solver =
            new TaskSelectionKnapsackSolver(new EffortEstimateResolver(new TaskSelectionProperties()));

    private static Task task(String title, int effortEstimateMinutes, Instant deadline) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTitle(title);
        task.setEffortEstimateMinutes(effortEstimateMinutes);
        task.setDeadline(deadline);
        return task;
    }

    private static List<String> titles(List<Task> tasks) {
        return tasks.stream().map(Task::getTitle).toList();
    }

    @Test
    void solve_tieAfterMinutesUsed_tieBreakByEarlierAverageDeadline() {
        Task laterDeadline = task("Later", 15, NOW.plus(Duration.ofHours(240)));
        Task earlierDeadline = task("Earlier", 15, NOW.plus(Duration.ofHours(24)));

        Map<Task, Double> scores = new LinkedHashMap<>();
        scores.put(laterDeadline, 0.5);
        scores.put(earlierDeadline, 0.5);

        List<Task> selected = solver.solve(scores, 15);

        assertThat(titles(selected)).containsExactly("Earlier");
    }
}
