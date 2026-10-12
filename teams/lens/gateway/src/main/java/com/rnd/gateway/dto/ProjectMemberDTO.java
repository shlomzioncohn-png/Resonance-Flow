package com.rnd.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A project's team member, for the Manager Dashboard project card. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMemberDTO {
    private String name;
    private String avatarUrl;
}
