package com.rnd.inbox.team;

import com.rnd.inbox.shared.messaging.validation.ValidUuid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class TeamCreateRequestDto {

    @NotBlank
    @ValidUuid
    private String tenantId;

    @NotBlank
    @Size(max = 255)
    private String name;

    @NotBlank
    @ValidUuid
    private String teamLeadId;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTeamLeadId() {
        return teamLeadId;
    }

    public void setTeamLeadId(String teamLeadId) {
        this.teamLeadId = teamLeadId;
    }
}
