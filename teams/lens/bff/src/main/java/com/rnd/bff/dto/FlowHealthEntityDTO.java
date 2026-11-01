package com.rnd.bff.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One element of a flag's entityIds. A task id gives one task; an edge
 * ("a>b") gives two, the dependent first, then the task it depends on.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlowHealthEntityDTO {

    @Schema(description = "The id exactly as Chain sent it")
    private String id;
    @Schema(description = "Task names joined with an arrow for edges", example = "Deploy API → Set up DB")
    private String label;
    private List<FlowHealthTaskRefDTO> tasks;
}
