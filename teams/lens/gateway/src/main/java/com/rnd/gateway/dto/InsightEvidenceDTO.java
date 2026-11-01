package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Evidence supporting a work insight — present only when status is READY.
 * Contains a text explanation and optionally achieved/total ratio.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InsightEvidenceDTO {

    /** Human-readable text explaining the evidence. Example: "12 out of 15 tasks with deadlines were completed on time last month". */
    private String text;

    /** Number of items achieved (e.g., tasks completed on time). Omitted if evidence is not a ratio. */
    private Integer achieved;

    /** Total number of items (e.g., total tasks with deadlines). Omitted if evidence is not a ratio. */
    private Integer total;
}
