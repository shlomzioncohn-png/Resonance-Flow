package com.rnd.inbox.team;

import com.rnd.inbox.shared.messaging.validation.ValidUuid;
import jakarta.validation.constraints.NotBlank;

public class TeamMemberRequestDto {

    @NotBlank
    @ValidUuid
    private String userId;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
