package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** GET /manager/flow-health - the manager's flow-health page: counts, last run and every flag (read-only). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowHealthDTO {

    /** Always in the JSON, as null when there are no flags yet: the page shows its empty state. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "When the analytics job last ran; null when there are no flags", nullable = true)
    private String lastRunAt;

    @Schema(description = "Flag count per pattern; all three patterns are always present")
    private Map<String, Integer> counts;

    @Schema(description = "Ordered by rank")
    private List<FlowHealthFlagDTO> flags;

    @Schema(description = "Title of every task id found in the flags' entities or evidence, for drawers to name tasks")
    private Map<String, String> taskNames;

    @Schema(description = "Every project seen in the flags, for the project filter")
    private List<FlowHealthProjectDTO> projects;
}
