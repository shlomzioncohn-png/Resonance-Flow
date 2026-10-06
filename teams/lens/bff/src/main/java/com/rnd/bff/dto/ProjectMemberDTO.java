package com.rnd.bff.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A project's team member, for the Manager Dashboard project card. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectMemberDTO {
    private String name;
    private String avatarUrl;
}
