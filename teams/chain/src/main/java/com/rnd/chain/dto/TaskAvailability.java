package com.rnd.chain.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class TaskAvailability {

    private final List<TaskResponse> blockingTasks;
    private final List<Constraint> openConstraints;
}