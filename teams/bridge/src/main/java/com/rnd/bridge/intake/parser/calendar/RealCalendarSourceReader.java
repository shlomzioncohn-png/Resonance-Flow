package com.rnd.bridge.intake.parser.calendar;

import com.rnd.bridge.intake.model.SourceRecord;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Placeholder for the real Google Calendar API reader (events.list, syncToken for incremental
 * sync — see plan.md). Not implemented yet; enabling bridge.intake.calendar.mode=real
 * activates this bean but every call fails until it's filled in.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.calendar", name = "mode", havingValue = "real")
public class RealCalendarSourceReader implements CalendarSourceReader {

    @Override
    public List<SourceRecord> read() {
        throw new UnsupportedOperationException(
                "Real Calendar reading is not implemented yet - see plan.md's rollout order");
    }
}
