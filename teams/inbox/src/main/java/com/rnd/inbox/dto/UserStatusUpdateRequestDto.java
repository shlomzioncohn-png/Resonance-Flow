package com.rnd.inbox.dto;

import com.rnd.inbox.messaging.validation.ValidEnum;
import com.rnd.inbox.model.enums.UserStatus;
import jakarta.validation.constraints.NotBlank;

public class UserStatusUpdateRequestDto {

    @NotBlank
    @ValidEnum(enumClass = UserStatus.class)
    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
