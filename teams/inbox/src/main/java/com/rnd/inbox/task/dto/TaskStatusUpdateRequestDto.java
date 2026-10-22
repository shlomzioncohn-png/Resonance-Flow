package com.rnd.inbox.task.dto;
import com.rnd.inbox.task.model.TaskStatus;
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

    // Optional, and accepted only when status is REJECTED - enforced in the Service
    // for the same reason as snoozedUntil above.
    private String feedbackText;

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

    public String getFeedbackText() {
        return feedbackText;
    }

    public void setFeedbackText(String feedbackText) {
        this.feedbackText = feedbackText;
    }
}
