package com.rnd.bff.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One page of tasks as Inbox returns it — mirrors Inbox's PageResponseDto
 * (snake_case on the wire: total_elements, total_pages).
 * InboxClient maps it to TaskListResponseDTO ({ items, total, page, size }).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class InboxPageResponse {

    private List<InboxTaskResponse> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
