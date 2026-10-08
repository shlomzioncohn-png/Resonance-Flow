package com.rnd.inbox.team.dto;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.shared.messaging.validation.ValidUuid;
import com.rnd.inbox.validation.OptionalNotBlank;
import jakarta.validation.constraints.Size;

public class TeamUpdateRequestDto {

    @OptionalNotBlank
    @Size(max = 255)
    private String name;

    // team_lead_id is required at the DB level (a Team always has exactly one lead) - unlike
    // Tenant.timezone or Task.description, there is no valid "clear it" action, so this is a
    // plain String + @OptionalNotBlank (omittable, never explicitly nullable), not JsonNullable.
    @OptionalNotBlank
    @ValidUuid
    private String teamLeadId;

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
