package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response for GET /manager/users — field names match the task spec exactly
 * (items/page/size/totalElements/totalPages). Mirrors
 * com.rnd.gateway.dto.UserListResponseDTO field-for-field.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserListResponseDTO {

    private List<UserRowDTO> items;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
