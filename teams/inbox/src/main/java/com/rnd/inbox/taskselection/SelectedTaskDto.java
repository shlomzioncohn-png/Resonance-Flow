package com.rnd.inbox.taskselection;

import java.time.Instant;
import java.util.UUID;

public final class SelectedTaskDto {

    private final UUID id;
    private final String title;
    private final String description;
    private final int effortEstimateMinutes;
    private final Instant deadline;
    private final double selectionScore;

    public SelectedTaskDto(UUID id, String title, String description, int effortEstimateMinutes, Instant deadline,
                            double selectionScore) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.effortEstimateMinutes = effortEstimateMinutes;
        this.deadline = deadline;
        this.selectionScore = selectionScore;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public int getEffortEstimateMinutes() {
        return effortEstimateMinutes;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public double getSelectionScore() {
        return selectionScore;
    }
}
