package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One task in a task selection (see TaskSelectionDTO). Mirrors
 * com.rnd.gateway.dto.SelectedTaskDTO field-for-field — each layer owns
 * its own copy of the DTO rather than sharing a library module.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SelectedTaskDTO {

    private String id;
    private String title;
    private String description;

    /** Minutes - Inbox's own estimate, used to fit the window. */
    private Integer effortEstimate;

    private LocalDateTime dueDate;

    /** 0-1, higher is a better pick. */
    private Double selectionScore;
}
