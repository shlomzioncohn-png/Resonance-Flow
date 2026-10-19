package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response for GET /api/manager/users — field names match the task spec
 * exactly (items/page/size/totalElements/totalPages), not the
 * items/total/page/size shape used elsewhere in lens (TaskListResponseDTO).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserListResponseDTO {

    private List<UserRowDTO> items;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
