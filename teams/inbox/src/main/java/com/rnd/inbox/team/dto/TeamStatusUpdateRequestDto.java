package com.rnd.inbox.team.dto;
import com.rnd.inbox.team.model.TeamStatus;
import com.rnd.inbox.shared.messaging.validation.ValidEnum;
import jakarta.validation.constraints.NotBlank;

public class TeamStatusUpdateRequestDto {

    @NotBlank
    @ValidEnum(enumClass = TeamStatus.class)
    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
