package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * One task in a task selection (see TaskSelectionDTO). Mirrors
 * com.rnd.bff.dto.SelectedTaskDTO field-for-field — each layer owns
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

    /** Already formatted for display by Inbox, Israel time: "12/10/2026" for dueDate, "12/10/2026 09:00" for the rest. */
    private String dueDate;

    /** 0-1, higher is a better pick. */
    private Double selectionScore;
}
