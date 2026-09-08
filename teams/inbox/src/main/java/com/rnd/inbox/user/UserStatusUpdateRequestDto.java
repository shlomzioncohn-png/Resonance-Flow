package com.rnd.inbox.user;

import com.rnd.inbox.shared.messaging.validation.ValidEnum;
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
