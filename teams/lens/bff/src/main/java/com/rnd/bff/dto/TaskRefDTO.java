package com.rnd.bff.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A lightweight reference to another task — used for blockedBy/blocks lists on EmployeeTaskDTO. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskRefDTO {
    private String id;
    private String title;
    private String owner;
}
