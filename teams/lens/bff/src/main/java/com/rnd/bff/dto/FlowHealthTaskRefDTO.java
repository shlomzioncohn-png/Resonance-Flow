package com.rnd.bff.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A task id with its Inbox title; the title falls back to the id when Inbox does not know the task. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlowHealthTaskRefDTO {

    private String id;
    private String title;
}
