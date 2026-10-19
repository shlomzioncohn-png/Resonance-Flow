package com.rnd.chain.dto;

import java.util.List;
import java.util.Objects;

public class TaskAvailability {

    private final List<TaskResponse> blockingTasks;
    private final List<Constraint> openConstraints;

    public TaskAvailability(
            List<TaskResponse> blockingTasks,
            List<Constraint> openConstraints) {

        this.blockingTasks =
                List.copyOf(
                        Objects.requireNonNull(
                                blockingTasks,
                                "blockingTasks must not be null"));

        this.openConstraints =
                List.copyOf(
                        Objects.requireNonNull(
                                openConstraints,
                                "openConstraints must not be null"));
    }

    public List<TaskResponse> getBlockingTasks() {
        return blockingTasks;
    }

    public List<Constraint> getOpenConstraints() {
        return openConstraints;
    }
}