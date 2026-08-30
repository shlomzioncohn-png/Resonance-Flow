package com.rnd.chain.dto;

import java.util.List;

public class TaskAvailability {

    private final List<Task> blockingTasks;
    private final List<Constraint> openConstraints;

    public TaskAvailability(
            List<Task> blockingTasks,
            List<Constraint> openConstraints
    ) {
        this.blockingTasks = blockingTasks;
        this.openConstraints = openConstraints;
    }

    public List<Task> getBlockingTasks() {
        return blockingTasks;
    }

    public List<Constraint> getOpenConstraints() {
        return openConstraints;
    }
}