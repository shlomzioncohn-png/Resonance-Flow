package com.rnd.inbox.tenant.dto;
import com.rnd.inbox.tenant.model.TenantStatus;
import com.rnd.inbox.shared.messaging.validation.ValidEnum;
import jakarta.validation.constraints.NotBlank;

public class TenantStatusUpdateRequestDto {

    @NotBlank
    @ValidEnum(enumClass = TenantStatus.class)
    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
