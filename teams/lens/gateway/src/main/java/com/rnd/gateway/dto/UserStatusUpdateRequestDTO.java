package com.rnd.gateway.dto;

import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of PATCH /api/manager/users/{id}/status. PENDING is deliberately not
 * in the allowed pattern — it isn't a transition the screen offers (task
 * spec §4: "PENDING לא נשלח מהמסך"), even though Inbox's own UserStatus
 * enum has it. Enforced again in ManagerUsersController (belt and
 * suspenders) rather than relying on this annotation alone.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserStatusUpdateRequestDTO {

    @Pattern(regexp = "ACTIVE|SUSPENDED|DISABLED", message = "status must be one of ACTIVE, SUSPENDED, DISABLED")
    private String status;
}
