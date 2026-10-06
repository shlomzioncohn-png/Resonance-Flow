package com.rnd.inbox.taskselection;

import com.rnd.inbox.task.model.Task;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class SelectionScoreCalculator {

    private static final double PRIORITY_WEIGHT = 0.45;
    private static final double DEADLINE_WEIGHT = 0.35;
    private static final double TIME_FIT_WEIGHT = 0.20;

    static {
        double weightSum = PRIORITY_WEIGHT + DEADLINE_WEIGHT + TIME_FIT_WEIGHT;
        if (Math.abs(weightSum - 1.0) > 1e-9) {
            throw new ExceptionInInitializerError(
                    "Selection Score weights must sum to 1.0, but sum to " + weightSum);
        }
    }

    private final TaskSelectionProperties properties;
    private final EffortEstimateResolver effortEstimateResolver;

    public SelectionScoreCalculator(TaskSelectionProperties properties,
                                     EffortEstimateResolver effortEstimateResolver) {
        this.properties = properties;
        this.effortEstimateResolver = effortEstimateResolver;
    }

    public double calculateSelectionScore(Task task, Instant now) {
        return compositeScore(task, now) * properties.getScale() + agingBoost(task, now);
    }

    public double compositeScore(Task task, Instant now) {
        return PRIORITY_WEIGHT * normalizedPriority(task)
                + DEADLINE_WEIGHT * normalizedDeadlineUrgency(task, now)
                + TIME_FIT_WEIGHT * normalizedTimeFit(task);
    }

    public double normalizedPriority(Task task) {
        return task.getPriorityScore() / 10.0;
    }

    public double normalizedDeadlineUrgency(Task task, Instant now) {
        Instant deadline = task.getDeadline();
        if (deadline == null) {
            return 0;
        }

        double priorityFactor = normalizedPriority(task);

        double hoursLeft = hoursBetween(now, deadline);
        if (hoursLeft > 0) {
            return priorityFactor * Math.exp(-hoursLeft / properties.getDeadlineHalfLifeHours());
        }

        double hoursOverdue = -hoursLeft;
        return priorityFactor * (1 + properties.getOverdueExtraCap()
                * (1 - Math.exp(-hoursOverdue / (24 * properties.getDeadlineHalfLifeHours()))));
    }

    public double normalizedTimeFit(Task task) {
        return effortEstimateResolver.resolvedEffortEstimateConfidence(task);
    }

    public double agingBoost(Task task, Instant now) {
        double hoursSinceCreated = hoursBetween(task.getCreatedAt(), now);
        return properties.getAgingCap() * (1 - Math.exp(-hoursSinceCreated / properties.getAgingHalfLifeHours()));
    }

    private double hoursBetween(Instant from, Instant to) {
        return Duration.between(from, to).toMillis() / 3_600_000.0;
    }
}
