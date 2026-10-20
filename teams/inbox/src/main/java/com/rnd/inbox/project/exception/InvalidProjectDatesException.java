package com.rnd.inbox.project.exception;

import com.rnd.inbox.shared.exception.InboxException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;

public class InvalidProjectDatesException extends InboxException {

    public InvalidProjectDatesException(LocalDate startDate, LocalDate targetDate) {
        super("target_date " + targetDate + " must not be before start_date " + startDate,
                "INVALID_PROJECT_DATES", HttpStatus.BAD_REQUEST);
    }
}
