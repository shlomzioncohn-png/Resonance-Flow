package com.rnd.inbox.task;

import com.rnd.inbox.shared.messaging.validation.ValidEnum;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public class TaskStatusUpdateRequestDto {

    @NotBlank
    @ValidEnum(enumClass = TaskStatus.class)
    private String status;

    // Required only when status is SNOOZED - enforced in the Service (mirrors the DB's own
    // chk_tasks_snoozed_until constraint), not here, since the rule depends on another field's
    // value rather than this field's shape alone.
    private Instant snoozedUntil;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getSnoozedUntil() {
        return snoozedUntil;
    }

    public void setSnoozedUntil(Instant snoozedUntil) {
        this.snoozedUntil = snoozedUntil;
    }
}
