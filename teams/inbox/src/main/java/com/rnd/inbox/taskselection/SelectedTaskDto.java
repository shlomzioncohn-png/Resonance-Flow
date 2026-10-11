package com.rnd.inbox.taskselection;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.rnd.inbox.shared.json.DisplayDateFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public final class SelectedTaskDto {

    private final UUID id;
    private final String title;
    private final String description;
    private final int effortEstimateMinutes;
    @JsonSerialize(using = DisplayDateFormat.DateOnlySerializer.class)
    @Schema(type = "string", example = "12/10/2026")
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
