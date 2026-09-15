package com.rnd.inbox.taskselection;

import com.rnd.inbox.task.Task;
import org.springframework.stereotype.Component;

@Component
public class EffortEstimateResolver {

    private final TaskSelectionProperties properties;

    public EffortEstimateResolver(TaskSelectionProperties properties) {
        this.properties = properties;
    }

    public int resolvedEffortEstimateMinutes(Task task) {
        Integer effortEstimateMinutes = task.getEffortEstimateMinutes();
        if (effortEstimateMinutes != null) {
            return effortEstimateMinutes;
        }
        return properties.getDefaultEffortEstimateMinutes();
    }

    public double resolvedEffortEstimateConfidence(Task task) {
        if (task.getEffortEstimateMinutes() == null) {
            return properties.getDefaultEffortEstimateConfidence();
        }
        Float effortEstimateConfidence = task.getEffortEstimateConfidence();
        return effortEstimateConfidence != null ? effortEstimateConfidence : 1.0;
    }
}
