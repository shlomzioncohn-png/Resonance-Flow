package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Chain's page wrapper (PageResponse) around its flow-health flags. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChainFlowHealthPageDTO {

    private List<ChainFlowHealthFlagDTO> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
