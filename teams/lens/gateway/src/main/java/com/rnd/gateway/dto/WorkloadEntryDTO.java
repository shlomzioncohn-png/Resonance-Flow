package com.rnd.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One employee's active-task count, for the Manager Workload screen. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkloadEntryDTO {
    private String employeeId;
    private String employeeName;
    private Integer activeTasks;
}
