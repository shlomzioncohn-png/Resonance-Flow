package com.rnd.chain.dto;

import java.util.List;
import java.util.stream.Collectors;

public class TaskAvailability {

    private final List<TaskResponse> blockingTasks;
    private final List<Constraint> openConstraints;

    public TaskAvailability(
            List<Task> blockingTasks,
            List<Constraint> openConstraints
    ) {
        this.blockingTasks = blockingTasks.stream()
                .map(TaskResponse::from)
                .collect(Collectors.toList());

        this.openConstraints = openConstraints;
    }

    public List<TaskResponse> getBlockingTasks() {
        return blockingTasks;
    }

    public List<Constraint> getOpenConstraints() {
        return openConstraints;
    }
}