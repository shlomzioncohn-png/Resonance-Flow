package com.rnd.inbox.insight.exception;

import com.rnd.inbox.shared.exception.InboxException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;

public class InvalidInsightPeriodException extends InboxException {

    public InvalidInsightPeriodException(LocalDate periodFrom, LocalDate periodTo) {
        super("period_to " + periodTo + " must not be before period_from " + periodFrom,
                "INVALID_INSIGHT_PERIOD", HttpStatus.BAD_REQUEST);
    }
}
