package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response for GET /workspace/tasks (the "My Tasks" screen's list, filter,
 * search, and pagination). Mirrors com.rnd.gateway.dto.TaskListResponseDTO
 * field-for-field — each layer owns its own copy of the DTO rather than
 * sharing a library module.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskListResponseDTO {

    /** The tasks on this page, after filter/search/pagination are applied. */
    private List<TaskDTO> items;

    /** Total matching tasks across all pages (before pagination, after filter/search). */
    private int total;

    /** The page number this response is for (0-based, echoes the request). */
    private int page;

    /** The page size this response used (echoes the request). */
    private int size;
}
